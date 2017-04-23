package arteria.core

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
    * Called when a channel is being established. After receiving this callback the channel is
    * ready to send messages.
    *
    * @param channel Channel that is being established
    */
  def establishing(channel: MessageChannel[ChannelProtocol]): Unit = {}

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
    * @param channelReader A reader for reading child channel creation and context data
    * @return Newly materialized channel
    */
  def materializeChildChannel(id: Int,
                              globalId: Int,
                              parent: MessageChannelBase,
                              channelReader: ChannelReader): MessageChannelBase = ???

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
    */
  def process: PartialFunction[Message, Unit] = PartialFunction.empty
}

object MessageChannelHandler {

  /**
    * Creates an empty channel handler that cannot process any messages
    */
  def empty[P <: Protocol] = new MessageChannelHandler[P] {}
}
