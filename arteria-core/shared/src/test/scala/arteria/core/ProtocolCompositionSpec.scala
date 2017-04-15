package arteria.core

import arteria.UnitSpec
import boopickle.Default._

class ProtocolCompositionSpec extends UnitSpec {
  object ProtocolA extends Protocol {
    case class AContext(a: String)

    override type ChannelContext = AContext

    override val contextPickler = implicitly[Pickler[AContext]]

    sealed trait AMsg extends Message

    case class AA1(i: Int)     extends AMsg
    case class AA2(b: Boolean) extends AMsg
    case class AA3(s: String)  extends AMsg

    val aPickler = compositePickler[AMsg]
      .addConcreteType[AA1]
      .addConcreteType[AA2]
      .addConcreteType[AA3]

    implicit val (messagePickler, witnessAMsg) = defineProtocol(aPickler)
  }

  class AHandler extends MessageChannelHandler[ProtocolA.type] {
    import ProtocolA._

    override def process = {
      case AA1(i) => println(s"Received A1($i)")
      case AA2(b) => println(s"Received A2($b)")
      case AA3(s) => println(s"Received A3($s)")
    }
  }

  object ProtocolB extends Protocol {
    case class BContext(b: String)

    override type ChannelContext = BContext

    override val contextPickler = implicitly[Pickler[BContext]]

    sealed trait BMsg extends Message

    case class BB1(i: Int)     extends BMsg
    case class BB2(b: Boolean) extends BMsg
    case class BB3(s: String)  extends BMsg

    val bPickler = compositePickler[BMsg]
      .addConcreteType[BB1]
      .addConcreteType[BB2]
      .addConcreteType[BB3]

    implicit val (messagePickler, witnessBMsg) = defineProtocol(bPickler)
  }

  class BHandler extends MessageChannelHandler[ProtocolB.type] {
    import ProtocolB._

    override def process = {
      case BB1(i) => println(s"Received B1($i)")
      case BB2(b) => println(s"Received B2($b)")
      case BB3(s) => println(s"Received B3($s)")
    }
  }

  object ProtocolC extends Protocol {

    case class CContext(actx: ProtocolA.AContext, bctx: ProtocolB.BContext, c: String)

    override type ChannelContext = CContext

    sealed trait CMsg extends Message

    case class C1(i: Int)     extends CMsg
    case class C2(b: Boolean) extends CMsg
    case class C3(s: String)  extends CMsg

    val cPickler = compositePickler[CMsg]
      .addConcreteType[C1]
      .addConcreteType[C2]
      .addConcreteType[C3]

    implicit val (messagePickler, witnessAMsg, witnessBMsg, witnessCMsg) = defineProtocol(ProtocolA.aPickler, ProtocolB.bPickler, cPickler)

    override val contextPickler = implicitly[Pickler[CContext]]
  }

  class CHandler(aHandler: AHandler, bHandler: BHandler) extends MessageChannelHandler[ProtocolC.type] {
    import ProtocolC._

    override def process = aHandler.process orElse bHandler.process orElse {
      case C1(i) => println(s"Received C1($i)")
      case C2(b) => println(s"Received C2($b)")
      case C3(s) => println(s"Received C3($s)")
    }
  }

  "Protocol composition" should "create a valid channel" in {
    val ah = new AHandler
    val bh = new BHandler
    val ch = new CHandler(ah, bh)

    val channel = new MessageChannel(ProtocolC)(0, 1, null, ch, ProtocolC.CContext(ProtocolA.AContext("a"), ProtocolB.BContext("b"), "c"))
  }
}
