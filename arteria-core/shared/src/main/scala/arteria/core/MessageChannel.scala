package arteria.core

import java.util.concurrent.atomic.AtomicInteger

import boopickle.{Pickler, UnpickleState}

import scala.collection.immutable.IntMap

/**
  * Base trait for all messages travelling on message channels. All your own message types must inherit from this.
  */
trait Message

/**
  * Defines a handler for a message channel.
  *
  * @tparam P Protocol that the channel is using
  */
trait MessageChannelHandler[P <: Protocol] {
  /**
    * An alias for protocol type
    */
  type ChannelProtocol = P

  /**
    * Called when a channel has been established. After receiving this callback the channel is
    * ready to accept messages in both directions.
    *
    * @param channel Channel that has been established
    */
  def established(channel: MessageChannel[ChannelProtocol]): Unit = {}

  /**
    * A request to materialize a child channel with the given parameters. This function is called when a remote router
    * has created a channel and the local router is establishing the channel.
    *
    * @param id            Channel identifier
    * @param globalId      Global channel identifier
    * @param parent        Parent channel
    * @param metadata      Metadata for materialization
    * @param contextReader A reader for reading channel context data
    * @return Newly materialized channel
    */
  def materializeChannel(id: Int, globalId: Int, parent: MessageChannelBase, metadata: ChannelProtocol#MaterializeMetadata,
    contextReader: ChannelReader): MessageChannelBase = ???

  /**
    * A child channel will close immediately. Use to perform internal clean-up
    *
    * @param id Identifies the channel that will close
    */
  def channelWillClose(id: Int): Unit = {}

  /**
    * This channel has been closed. Can be used to free any allocated resources and to inform other
    * parties about channel closing
    */
  def closed(): Unit = {}

  /**
    * Called for every message received
    *
    * @param message
    */
  def process(message: Message): Unit = {}
}

/**
  * Base functionality for a message channel
  */
trait MessageChannelBase {
  type MaterializeMetadata
  def materializeMetadataPickler: Pickler[MaterializeMetadata]

  protected[core] var subChannels = IntMap.empty[MessageChannelBase]
  protected[core] val channelIdx = new AtomicInteger(1)

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
    * @param channelReader Reader for accessing pickled message data
    */
  def receiveDrop(channelReader: ChannelReader): Unit

  /**
    * Creates a new child channel with the given protocol and parameters
    *
    * @param protocol Protocol for the new child channel
    * @param handler  Handler for the channel
    * @param context  Context to be passed to the channel
    * @param metadata Metadata that is used to materialize the channel at the other end
    * @tparam P Protocol type
    * @return Newly created channel
    */
  def createChannel[P <: Protocol](protocol: P)
    (handler: MessageChannelHandler[P], context: protocol.ChannelContext, metadata: MaterializeMetadata): MessageChannel[P] = {
    val channelId = channelIdx.getAndIncrement()
    val channelGlobalId = router.nextGlobalId
    val channel = new MessageChannel(protocol)(channelId, channelGlobalId, this, handler, context)
    subChannels = subChannels.updated(channelId, channel)
    // inform our counterpart and get our global id
    router.establishChannel(channel, context, metadata)(protocol.contextPickler, materializeMetadataPickler)
    channel
  }

  /**
    * Close a previously created child channel.
    *
    * @param channel
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
  protected[core] def materializeChannel(channelId: Int, channelGlobalId: Int, channelReader: ChannelReader): MessageChannelBase

  /**
    * Called when a child channel is closed.
    *
    * @param channelId Channel identifier
    */
  protected[core] def channelClosed(channelId: Int): Unit

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

  override type MaterializeMetadata = protocol.MaterializeMetadata
  override def materializeMetadataPickler: Pickler[MaterializeMetadata] = protocol.materializeMetadataPickler

  /**
    * Sends a message on this channel. Message type is checked using an implicit `MessageWitness`.
    *
    * @param message Message to send
    * @param ev      Provides evidence that message is valid for the protocol `P`
    * @tparam A Type of the message
    */
  def send[A <: Message](message: A)(implicit ev: MessageWitness[A, P]): Unit = {
    router.send(message, globalId)(protocol.messagePickler)
  }

  override def receive(channelReader: ChannelReader): Unit = {
    val msg = channelReader.read(protocol.messagePickler)
    handler.process(msg)
  }

  override def receiveDrop(channelReader: ChannelReader): Unit = {
    val msg = channelReader.read(protocol.messagePickler)
  }

  protected[core] override def materializeChannel(channelId: Int, globalId: Int, channelReader: ChannelReader): MessageChannelBase = {
    // read the metadata
    val metadata = channelReader.read(protocol.materializeMetadataPickler)
    val channel = handler.materializeChannel(id, globalId: Int, this, metadata, channelReader)
    subChannels = subChannels.updated(id, channel)
    channel.established()
    channel
  }

  protected[core] def channelClosed(channelId: Int): Unit = {
    subChannels.get(channelId) match {
      case Some(channel) =>
        handler.channelWillClose(channelId)
        channel.close()
        subChannels -= channelId
      case None =>
        throw new IllegalArgumentException(s"Channel $channelId is not a sub channel of $id")
    }
  }

  protected[core] override def established(): Unit = {
    handler.established(this)
  }

  override def close(): Unit = {
    super.close()
    handler.closed()
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

