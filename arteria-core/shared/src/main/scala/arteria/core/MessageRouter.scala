package arteria.core

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

import boopickle.Default._

import scala.collection.immutable.IntMap

object MessageRouter {
  final val MessageTag      = 0x10000000
  final val MessageMask     = 0x0FFFFFFF
  final val StartTag        = 0x20000000
  final val EndTag          = 0x30000000
  final val RouterChannelId = 0

  sealed trait ChannelState {
    def canSend: Boolean
  }

  case object StateOpening extends ChannelState {
    override def canSend: Boolean = true
  }

  case object StateEstablished extends ChannelState {
    override def canSend: Boolean = true
  }

  case object StateClosed extends ChannelState {
    override def canSend: Boolean = false
  }

  case object StateClosing extends ChannelState {
    override def canSend: Boolean = false
  }

  case class Channel(channel: MessageChannelBase, state: ChannelState)

  /**
    * Base trait for internal router messages
    */
  sealed trait RouterMessage extends Message

  case class EstablishRoute(isPrimary: Boolean) extends RouterMessage

  case class EstablishChannel(channelGlobalId: Int, channelId: Int, parentGlobalId: Int) extends RouterMessage

  case class ChannelEstablished(channelGlobalId: Int) extends RouterMessage

  case class CloseChannel(channelGlobalId: Int) extends RouterMessage

  case class ChannelClosed(channelGlobalId: Int) extends RouterMessage

  implicit val rmPickler = compositePickler[RouterMessage]
    .addConcreteType[EstablishRoute]
    .addConcreteType[EstablishChannel]
    .addConcreteType[ChannelEstablished]
    .addConcreteType[CloseChannel]
    .addConcreteType[ChannelClosed]
}

/**
  * Interface for a message router
  */
trait MessageRouterBase extends MessageChannelBase {

  /**
    * Sends a message over to the other side.
    *
    * @param message         Message to be sent
    * @param channelGlobalId Global channel identifier
    * @param messagePickler  Pickler for the message type
    */
  def send(message: Message, channelGlobalId: Int)(implicit messagePickler: Pickler[Message]): Unit

  /**
    * Returns the next available global channel identifier
    *
    * @return
    */
  def nextGlobalId: Int

  /**
    * Establish a channel between this and the remote router.
    *
    * @param channel   Channel to be established
    * @param context   Context to be passed to the materialized channel on the other side.
    * @param metadata  Metadata to be passed to the parent channel on the other side to assist in
    *                  materializing the correct channel.
    * @param cPickler  Pickler for context
    * @param mdPickler Pickler for metadata
    * @tparam C  Type of the channel context
    * @tparam MD Type of the materialization metadata
    * @return
    */
  def establishChannel[C, MD](channel: MessageChannelBase, context: C, metadata: MD)(
      implicit cPickler: Pickler[C],
      mdPickler: Pickler[MD]): MessageChannelBase

  /**
    * Closes a channel
    *
    * @param channelGlobalId Global channel identifier
    */
  def closeChannel(channelGlobalId: Int): Unit
}

/**
  * Message router is a special message channel that sits underneath all other channels. It handles the actual
  * pickling of channel and control messages and maintains an internal list of active channels. When a message
  * is received, the router will route it to the correct `MessageChannel` instance.
  *
  * @param handler   Handler for this router instance
  * @param isPrimary Set to `true` is this is the primary router. The other router will then be the secondary
  * @tparam MaterializeChild Type for materialization metadata, used when creating new channels under the router
  */
class MessageRouter[MaterializeChild: Pickler](handler: MessageRouterHandler[MaterializeChild], isPrimary: Boolean)
    extends MessageRouterBase {
  import MessageRouter._

  protected[core] var globalChannels     = IntMap[Channel](0 -> Channel(this, StateOpening))
  protected[core] val globalChannelIdx   = new AtomicInteger(if (isPrimary) 0x1000 else 0x08001000)
  protected var pickleState: PickleState = _
  protected var pendingCount             = 0

  override def id = RouterChannelId

  override def globalId: Int = id

  override def parent = this

  override lazy val router = this

  // create initial pickle state
  reset()
  // establish the route
  send(EstablishRoute(isPrimary))

  /**
    * Resets by creating a new pickle state
    */
  def reset(): Unit = {
    // reset state
    pickleState = handler.pickleStateFactory
    pickleState.enc.writeInt(StartTag)
    pendingCount = 0
  }

  /**
    * Checks if there are pending messages that have not been flushed.
    *
    * @return
    */
  def hasPending = pendingCount > 0

  /**
    * Returns a `PickleState` containing serialized messages and resets the state. This allows
    * the caller to continue pickling other data into the same stream.
    */
  def flushState(): PickleState = {
    // mark end of messages
    pickleState.enc.writeInt(EndTag)
    val ps = pickleState
    reset()
    ps
  }

  /**
    * Returns a `ByteBuffer` containing serialized messages and reset state
    */
  def flush(): ByteBuffer = {
    flushState().toByteBuffer
  }

  /**
    * Processes received data
    *
    * @param data A `ByteBuffer` containing messages to process
    */
  def receive(data: ByteBuffer): Unit = {
    receive(handler.unpickleStateFactory(data))
  }

  /**
    * Processes received data.
    *
    * @param unpickleState An `UnpickleState` containing messages to process
    */
  def receive(unpickleState: UnpickleState): Unit = {
    // check we have a correct header
    val start = unpickleState.dec.readInt
    if (start != StartTag)
      throw new IllegalStateException(f"Message stream did not start with StartTag (found $start%08x)")

    // process all incoming messages
    var endOfMessages = false
    while (!endOfMessages) {
      val tag = unpickleState.dec.readInt
      tag & ~MessageMask match {
        case MessageTag =>
          val channelId = tag & MessageMask
          globalChannels.get(channelId) match {
            case Some(Channel(channel, state)) if state == StateOpening || state == StateEstablished =>
              channel.receive(new ChannelReaderImpl(unpickleState))
            case Some(Channel(channel, StateClosing)) =>
              // channel is closing drop incoming messages
              channel.receiveDrop(new ChannelReaderImpl(unpickleState))
            case Some(Channel(_, StateClosed)) =>
              // channel is gone, cannot read its messages
              throw new IllegalStateException(s"Received a message for a closed channel ($channelId)")
            case None =>
              throw new IllegalStateException(s"Encountered unknown channel ID ($channelId) in stream")
          }
        case StartTag =>
          throw new IllegalStateException("Unexpected StartTag in stream")
        case EndTag =>
          endOfMessages = true
        case unknown =>
          throw new IllegalStateException(f"Encountered unknown tag type $unknown%08x in stream")
      }
    }
  }

  override def receive(channelReader: ChannelReader): Unit = {
    val msg = channelReader.read[RouterMessage]
    process(msg, channelReader)
  }

  override def receiveDrop(channelReader: ChannelReader): Unit = {
    channelReader.read[RouterMessage]
    ()
  }

  override def send(message: Message, channelGlobalId: Int)(implicit messagePickler: Pickler[Message]): Unit = {
    globalChannels.get(channelGlobalId) match {
      case Some(Channel(channel, state)) if state.canSend =>
        pendingCount += 1
        pickleState.enc.writeInt(channelGlobalId | MessageTag)
        pickleState.pickle(message)
        handler.messagesPending(pendingCount)
      case _ =>
        println(s"Channel $channelGlobalId cannot send messages")
      // ignore otherwise
    }
  }

  private def send(message: RouterMessage): Unit = {
    pickleState.enc.writeInt(id | MessageTag)
    pickleState.pickle(message)
  }

  def process(message: RouterMessage, channelReader: ChannelReader): Unit = {
    message match {
      case EstablishRoute(otherIsPrimary) =>
        println(f"Route established")
        if (otherIsPrimary == isPrimary)
          throw new IllegalStateException("Both routers are trying to be primary")
        globalChannels = globalChannels.updated(globalId, Channel(this, StateEstablished))

      case EstablishChannel(channelGlobalId, channelId, parentGlobalId) =>
        globalChannels.get(parentGlobalId) match {
          case Some(Channel(parent, StateEstablished)) =>
            println(f"Establish channel: $channelGlobalId%08x - $channelId%08x parent $parentGlobalId%08x")
            send(ChannelEstablished(channelGlobalId))
            val channel = parent.materializeChildChannel(channelId, channelGlobalId, channelReader)
            globalChannels = globalChannels.updated(channelGlobalId, Channel(channel, StateEstablished))
            channel.establishing()
            channel.established()
          case Some(Channel(_, _)) =>
            throw new IllegalArgumentException(s"EstablishChannel: Parent channel $parentGlobalId was not established")
          case None =>
            throw new IllegalArgumentException(s"EstablishChannel: Parent channel $parentGlobalId was not found")
        }

      case ChannelEstablished(channelGlobalId) =>
        globalChannels.get(channelGlobalId) match {
          case Some(Channel(channel, StateOpening)) =>
            println(f"Channel $channelGlobalId%08x established")
            globalChannels = globalChannels.updated(channelGlobalId, Channel(channel, StateEstablished))
            channel.established()
          case Some(Channel(_, _)) =>
          // ignore other states
          case None =>
            throw new IllegalArgumentException(s"ChannelEstablished: Channel $channelGlobalId was not found")
        }

      case CloseChannel(channelGlobalId) =>
        println(f"Channel $channelGlobalId%08x is closing")
        globalChannels.get(channelGlobalId) match {
          case Some(Channel(channel, state)) if state == StateClosed =>
          // channel is already closed on our side, do nothing
          case Some(Channel(channel, _)) if channel.id == RouterChannelId =>
            // router is closing down
            handler.routerWillClose()
          case Some(Channel(channel, _)) =>
            channel.parent.channelClosed(channel.id)
            globalChannels = globalChannels.updated(channelGlobalId, Channel(channel, StateClosed))
            send(ChannelClosed(channelGlobalId))
          case None =>
            throw new IllegalArgumentException(s"CloseChannel: Channel $channelGlobalId was not found")
        }

      case ChannelClosed(channelGlobalId) =>
        println(f"Channel $channelGlobalId%08x is closed")
        globalChannels.get(channelGlobalId) match {
          case Some(Channel(channel, StateClosing)) =>
            channel.parent.channelClosed(channel.id)
            globalChannels = globalChannels.updated(channelGlobalId, Channel(null, StateClosed))
          case Some(Channel(channel, state)) =>
            throw new IllegalStateException(s"Channel ${channel.globalId} is not in closing state [$state]")
          case None =>
            throw new IllegalArgumentException(s"ChannelClosed: Channel $channelGlobalId was not found")
        }
    }
  }

  override def nextGlobalId: Int = globalChannelIdx.getAndIncrement()

  override def establishChannel[C, MD](channel: MessageChannelBase, context: C, metadata: MD)(
      implicit cPickler: Pickler[C],
      mdPickler: Pickler[MD]): MessageChannelBase = {
    globalChannels = globalChannels.updated(channel.globalId, Channel(channel, StateOpening))
    send(EstablishChannel(channel.globalId, channel.id, channel.parent.globalId))
    // append metadata
    pickleState.pickle(metadata)(mdPickler)
    // append context
    pickleState.pickle(context)(cPickler)
    channel
  }

  def closeChannel(channelGlobalId: Int): Unit = {
    globalChannels.get(channelGlobalId) match {
      case Some(channel) =>
        globalChannels = globalChannels.updated(channelGlobalId, channel.copy(state = StateClosing))
        // send a message to my counterpart
        send(CloseChannel(channelGlobalId))
      case None =>
        throw new IllegalArgumentException(s"Channel $channelGlobalId is not recognized")
    }
  }

  /**
    * Creates a new child channel with the given protocol and parameters
    *
    * @param protocol         Protocol for the new child channel
    * @param handler          Handler for the channel
    * @param context          Context to be passed to the channel
    * @param materializeChild Metadata that is used to materialize the channel at the other end
    * @tparam CP Protocol type
    * @return Newly created channel
    */
  def createChannel[CP <: Protocol](
      protocol: CP)(handler: MessageChannelHandler[CP], context: CP#ChannelContext, materializeChild: MaterializeChild)(
      implicit materializeChildPickler: Pickler[MaterializeChild]): MessageChannel[CP] = {
    val channelId       = channelIdx.getAndIncrement()
    val channelGlobalId = router.nextGlobalId
    val channel         = new MessageChannel(protocol)(channelId, channelGlobalId, this, handler, context)
    subChannels = subChannels.updated(channelId, channel)
    // inform our counterpart
    router.establishChannel(channel, context, materializeChild)(
      protocol.contextPickler.asInstanceOf[Pickler[CP#ChannelContext]],
      materializeChildPickler
    )
    handler.establishing(channel)
    channel
  }

  protected[core] override def materializeChildChannel(channelId: Int,
                                                       globalId: Int,
                                                       channelReader: ChannelReader): MessageChannelBase = {
    val subMetadata = channelReader.read[MaterializeChild]
    val channel     = handler.materializeChildChannel(channelId, globalId, this, subMetadata, channelReader).get
    channel
  }

  protected[core] override def channelClosed(channelId: Int): Unit = {
    handler.channelWillClose(channelId)
  }
}
