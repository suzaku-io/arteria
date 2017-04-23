package arteria.core

import java.util.concurrent.atomic.AtomicInteger

import boopickle.{Pickler, UnpickleState}

import scala.collection.immutable.IntMap

/**
  * Base trait for all messages travelling on message channels. All your own message types must inherit from this.
  */
trait Message

/**
  * Base functionality for a message channel
  */
trait MessageChannelBase {
  protected[core] var subChannels = IntMap.empty[MessageChannelBase]
  protected[core] val channelIdx  = new AtomicInteger(1)

  def id: Int

  def globalId: Int

  def parent: MessageChannelBase

  lazy val router: MessageRouterBase = parent.router

  /**
    * Receive and process a message.
    *
    * @param channelReader Reader for accessing pickled message data
    */
  def receive(channelReader: ChannelReader): Unit

  /**
    * Receives and drops a message
    *
    * @param channelReader Reader for accessing pickled message data
    */
  def receiveDrop(channelReader: ChannelReader): Unit

  /**
    * Close a previously created child channel.
    *
    * @param channel The channel to be closed
    */
  def closeChannel(channel: MessageChannelBase): Unit = {
    if (subChannels.contains(channel.id)) {
      // close the channel and its sub-channels
      channel.close()
      // inform our counterpart
      router.closeChannel(channel.globalId)
      subChannels -= channel.id
    } else {
      throw new IllegalArgumentException(s"Channel ${channel.id} is not a sub channel of $id")
    }
  }

  /**
    * Materializes a new child channel by reading metadata from the stream and passing it to the handler.
    *
    * @param channelId            Channel identifier
    * @param channelGlobalId      Global channel identifier
    * @param channelReader        Reader for accessing the data stream
    * @return
    */
  protected[core] def materializeChildChannel(channelId: Int,
                                              channelGlobalId: Int,
                                              channelReader: ChannelReader): MessageChannelBase

  /**
    * Called when a child channel is closed.
    *
    * @param channelId Channel identifier
    */
  protected[core] def channelClosed(channelId: Int): Unit

  /**
    * Called when channel is being established
    */
  protected[core] def establishing(): Unit = {}

  /**
    * Called when the other side has established the channel.
    */
  protected[core] def established(): Unit = {}

  /**
    * Close this channel
    */
  def close(): Unit = {
    // close all sub channels
    subChannels.values.toVector.foreach(c => closeChannel(c))
    router.closeChannel(globalId)
  }
}

/**
  * `MessageChannel` provides a communication channel with two end-points and allows sending and receiving messages in both directions.
  *
  * @param protocol Protocol implementation for this channel
  * @param id       Channel identifier
  * @param parent   Parent channel
  * @param handler  Handler for channel related activity like processing incoming messages
  * @param context  Context for this instance of the channel
  * @tparam P Protocol type
  */
class MessageChannel[P <: Protocol](val protocol: P)(
    val id: Int,
    val globalId: Int,
    val parent: MessageChannelBase,
    val handler: MessageChannelHandler[P],
    val context: P#ChannelContext
) extends MessageChannelBase {

  private val handlerFunc = handler.process.lift

  override def receive(channelReader: ChannelReader): Unit = {
    val msg = channelReader.read(protocol.messagePickler)
    if (handlerFunc(msg).isEmpty)
      throw new MatchError(msg)
  }

  override def receiveDrop(channelReader: ChannelReader): Unit = {
    channelReader.read(protocol.messagePickler)
    ()
  }

  protected[core] override def materializeChildChannel(channelId: Int,
                                                       globalId: Int,
                                                       channelReader: ChannelReader): MessageChannelBase = {
    val channel = handler.materializeChildChannel(channelId, globalId, this, channelReader)
    subChannels = subChannels.updated(channelId, channel)
    channel
  }

  protected[core] def channelClosed(channelId: Int): Unit = {
    subChannels.get(channelId) match {
      case Some(channel) =>
        handler.channelWillClose(channelId)
        subChannels -= channelId
      case None =>
        throw new IllegalArgumentException(s"Channel $channelId is not a sub channel of $id")
    }
  }

  protected[core] override def establishing(): Unit = {
    handler.establishing(this)
  }

  protected[core] override def established(): Unit = {
    handler.established(this)
  }

  override def close(): Unit = {
    super.close()
    handler.closed()
  }

  /**
    * Sends a message on this channel. Message type is checked using an implicit `MessageWitness`.
    *
    * @param message Message to send
    * @param ev      Provides evidence that message is valid for the protocol `P`
    * @tparam A Type of the message
    */
  def send[A <: Message](message: A)(implicit ev: MessageWitness[A, P]): Unit = {
    val _ = ev // workaround for a warning on unused implicit parameter
    router.send(message, globalId)(protocol.messagePickler)
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
  def createChannel[MaterializeChild, CP <: Protocol](
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
}

/**
  * `ChannelReader` provides functions outside the `MessageChannel` a way to read data from the stream
  */
trait ChannelReader {
  def read[A](implicit pickler: Pickler[A]): A
}

private[core] class ChannelReaderImpl(unpickleState: UnpickleState) extends ChannelReader {
  def read[A](implicit pickler: Pickler[A]): A = unpickleState.unpickle(pickler)
}
