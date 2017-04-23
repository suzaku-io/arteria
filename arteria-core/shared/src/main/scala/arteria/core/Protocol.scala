package arteria.core

import boopickle.{CompositePickler, Pickler}

import scala.annotation.implicitNotFound

/**
  * Provides evidence that a message of type `M` (or any of its descendants) is valid for protocol `P`
  *
  * @tparam M Message type
  * @tparam P Protocol type
  */
@implicitNotFound("Message of type ${M} is not valid for protocol ${P}")
trait MessageWitness[-M <: Message, P <: Protocol] extends DummyImplicit

/**
  * A `Protocol` is used to define the communication protocol on a `MessageChannel`
  */
trait Protocol {

  /**
    * Type for the context that gets passed to the channel when it's materialized
    */
  type ChannelContext

  /**
    * A pickler for all the supported message types in this protocol
    *
    * @return
    */
  val messagePickler: Pickler[Message]

  /**
    * A pickler for the channel context type
    *
    * @return
    */
  val contextPickler: Pickler[ChannelContext]

  /**
    * A helper function to provide evidence (witness) that a message type is supported by this protocol.
    *
    * @tparam M Type of the message (or a root of a message hierarchy)
    * @return
    */
  protected def witnessFor[M <: Message]: MessageWitness[M, this.type] = new MessageWitness[M, this.type] {}

  /**
    * Helper function to define a protocol composed of multiple message types
    */
  protected def defineProtocol[M1 <: Message](cp1: CompositePickler[M1]) = (
    new CompositePickler[Message].join(cp1),
    witnessFor[M1]
  )

  /**
    * Helper function to define a protocol composed of multiple message types
    */
  protected def defineProtocol[M1 <: Message, M2 <: Message](cp1: CompositePickler[M1], cp2: CompositePickler[M2]) = (
    new CompositePickler[Message].join(cp1).join(cp2),
    witnessFor[M1],
    witnessFor[M2]
  )

  /**
    * Helper function to define a protocol composed of multiple message types
    */
  protected def defineProtocol[M1 <: Message, M2 <: Message, M3 <: Message](cp1: CompositePickler[M1],
                                                                            cp2: CompositePickler[M2],
                                                                            cp3: CompositePickler[M3]) = (
    new CompositePickler[Message].join(cp1).join(cp2).join(cp3),
    witnessFor[M1],
    witnessFor[M2],
    witnessFor[M3]
  )

  /**
    * Helper function to define a protocol composed of multiple message types
    */
  protected def defineProtocol[M1 <: Message, M2 <: Message, M3 <: Message, M4 <: Message](cp1: CompositePickler[M1],
                                                                                           cp2: CompositePickler[M2],
                                                                                           cp3: CompositePickler[M3],
                                                                                           cp4: CompositePickler[M4]) = (
    new CompositePickler[Message].join(cp1).join(cp2).join(cp3).join(cp4),
    witnessFor[M1],
    witnessFor[M2],
    witnessFor[M3],
    witnessFor[M4]
  )

  /**
    * Helper function to define a protocol composed of multiple message types
    */
  protected def defineProtocol[M1 <: Message, M2 <: Message, M3 <: Message, M4 <: Message, M5 <: Message](
      cp1: CompositePickler[M1],
      cp2: CompositePickler[M2],
      cp3: CompositePickler[M3],
      cp4: CompositePickler[M4],
      cp5: CompositePickler[M5]) = (
    new CompositePickler[Message].join(cp1).join(cp2).join(cp3).join(cp4).join(cp5),
    witnessFor[M1],
    witnessFor[M2],
    witnessFor[M3],
    witnessFor[M4],
    witnessFor[M5]
  )
}
