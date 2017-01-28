package arteria.core

import java.nio.ByteBuffer

import boopickle.Default.{PickleState, UnpickleState}
import boopickle.{DecoderSpeed, EncoderSpeed, PickleState, UnpickleState}

/**
  * External interface for the router.
  *
  * @tparam MaterializeChild Type for materialization metadata, used when creating new channels under the router
  */
trait MessageRouterHandler[MaterializeChild] {

  /**
    * Builds a new instance of `PickleState` when needed for pickling messages.
    */
  def pickleStateFactory: PickleState = new PickleState(new EncoderSpeed(), false, false)

  /**
    * Builds a new instance of `UnpickleState` when needed for unpickling messages.
    */
  def unpickleStateFactory(bb: ByteBuffer): UnpickleState = new UnpickleState(new DecoderSpeed(bb), false, false)

  /**
    * Called when a new child channel needs to be created under the router.
    *
    * @param id                    Channel identifier
    * @param globalId              Global identifier for the channel
    * @param router                The router
    * @param materializeChild      Metadata needed for creating a correct channel
    * @param contextReader         Reader for reading additional data from the stream
    * @return A newly created `MessageChannel`
    */
  def materializeChildChannel(id: Int,
                              globalId: Int,
                              router: MessageRouterBase,
                              materializeChild: MaterializeChild,
                              contextReader: ChannelReader): Option[MessageChannelBase]

  /**
    * Called when a child channel will be closed.
    *
    * @param globalId Global channel identifier
    */
  def channelWillClose(globalId: Int): Unit = {}

  /**
    * Called when the router itself is closing due to a request from the other router.
    */
  def routerWillClose(): Unit = {}

  /**
    * Called when a message has been queued by the router. This callback can be used to determine when
    * there are pending messages that can be flushed.
    */
  def messagesPending(count: Int): Unit = {}
}
