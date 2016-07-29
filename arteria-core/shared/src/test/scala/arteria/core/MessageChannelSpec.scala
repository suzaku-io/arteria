package arteria.core

import java.nio.{ByteBuffer, ByteOrder}

import boopickle.Default._
import boopickle.{DecoderSpeed, EncoderSpeed}

import arteria.UnitSpec

sealed trait MainChannelMetadata

case object CreateSystemChannel extends MainChannelMetadata

sealed trait CommonMessage extends Message

case class Ping(time: Long) extends CommonMessage

object CommonMessage {
  val pickler = compositePickler[CommonMessage]
    .addConcreteType[Ping]
}

object SystemProtocol extends Protocol {
  override type ChannelContext = SystemChannelContext

  case class SystemChannelContext(name: String)

  sealed trait SystemMessage extends Message

  case class System1(i: Int) extends SystemMessage

  private val sPickler = compositePickler[SystemMessage]
    .addConcreteType[System1]

  implicit val (messagePickler, commonMsg, sysMsg) = defineProtocol(CommonMessage.pickler, sPickler)

  override val contextPickler = implicitly[Pickler[SystemChannelContext]]

  override def materializeChannel(id: Int, globalId: Int, router: MessageRouterBase, handler: MessageChannelHandler[This],
    context: SystemChannelContext): MessageChannel[This] = {
    val channel = new MessageChannel(this)(id, globalId, router, handler, context)
    channel
  }
}

object RandomProtocol extends Protocol {
  override type ChannelContext = RandomProtocolContext

  case class RandomProtocolContext(index: Int)

  sealed trait RandomMessage extends Message

  case class GenRandom(maxValue: Int) extends RandomMessage

  case class RandomValue(value: Int) extends RandomMessage

  private val tPickler = compositePickler[RandomMessage]
    .addConcreteType[GenRandom]
    .addConcreteType[RandomValue]

  implicit val (messagePickler, testMsg) = defineProtocol(tPickler)

  override def contextPickler = implicitly[Pickler[RandomProtocolContext]]

  override def materializeChannel(id: Int, globalId: Int, router: MessageRouterBase, handler: MessageChannelHandler[RandomProtocol.This],
    context: RandomProtocolContext): MessageChannel[RandomProtocol.This] = {
    val channel = new MessageChannel(this)(id, globalId, router, handler, context)
    channel
  }
}

class RandomHandler extends MessageChannelHandler[RandomProtocol.type] {
  import RandomProtocol._
  var receivedMessages = Vector.empty[Message]
  var channel: MessageChannel[ChannelProtocol] = _
  var receivedValue: Int = -1

  override def established(channel: MessageChannel[ChannelProtocol]): Unit = {
    this.channel = channel
  }

  override def process(message: Message): Unit = {
    println(s"System received: $message")
    receivedMessages :+= message
    message match {
      case GenRandom(maxValue) =>
        channel.send(RandomValue(math.min(9, maxValue)))
      case RandomValue(value) =>
        receivedValue = value
    }
  }
}

class TestSystemHandler extends MessageChannelHandler[SystemProtocol.type] {
  var receivedMessages = Vector.empty[Message]
  var channel: MessageChannel[ChannelProtocol] = _

  override def established(channel: MessageChannel[ChannelProtocol]): Unit = {
    this.channel = channel
  }

  override def process(message: Message): Unit = {
    println(s"System received: $message")
    receivedMessages :+= message
  }

  override def materializeChildChannel(id: Int, globalId: Int, parent: MessageChannelBase, channelReader: ChannelReader): MessageChannelBase = {
    val materializeChild = channelReader.read[String]
    assert(materializeChild == "random")
    val context = channelReader.read[RandomProtocol.ChannelContext]
    val channel = new MessageChannel(RandomProtocol)(id, globalId, parent, new RandomHandler, context)
    channel
  }
}

class TestRouterHandler(val systemHandler: MessageChannelHandler[SystemProtocol.type]) extends MessageRouterHandler[MainChannelMetadata] {
  import SystemProtocol._

  var materializeRequests = Vector.empty[Int]
  var closeRequests = Vector.empty[Int]

  override def pickleStateFactory: PickleState =
    new PickleState(new EncoderSpeed(), false, false)

  override def unpickleStateFactory(bb: ByteBuffer): UnpickleState =
    new UnpickleState(new DecoderSpeed(bb), false, false)

  override def materializeChildChannel(id: Int, globalId: Int, router: MessageRouterBase, materializeChild: MainChannelMetadata,
    contextReader: ChannelReader): MessageChannelBase = {
    materializeRequests :+= globalId
    materializeChild match {
      case CreateSystemChannel =>
        println(s"createChannel called with id = $id")
        val context = contextReader.read[SystemChannelContext]
        SystemProtocol.materializeChannel(id, globalId, router, systemHandler, context)
    }
  }

  override def channelWillClose(globalId: Int): Unit = {
    closeRequests :+= globalId
  }
}

class MessageChannelSpec extends UnitSpec {
  import SystemProtocol._

  def routerHandler(systemHandler: MessageChannelHandler[SystemProtocol.type]) = new TestRouterHandler(systemHandler)

  def router(handler: MessageRouterHandler[MainChannelMetadata], isPrimary: Boolean) = new MessageRouter(handler, isPrimary)

  def defaultRouterA = router(new TestRouterHandler(new TestSystemHandler), true)

  def defaultRouterB = router(new TestRouterHandler(new TestSystemHandler), false)

  def initRouters = {
    // init routers and set up channel
    val routerA = defaultRouterA
    val systemHandlerA = new TestSystemHandler
    val systemHandlerB = new TestSystemHandler
    val routerB = router(new TestRouterHandler(systemHandlerB), false)
    val channel = routerA.createChannel(SystemProtocol)(systemHandlerA, SystemChannelContext("system"), CreateSystemChannel)
    runRouters(routerA, routerB)

    (routerA, routerB, systemHandlerA, systemHandlerB, channel)
  }

  def runRouters(routerA: MessageRouter[_], routerB: MessageRouter[_]): Unit = {
    val bbA = routerA.flush()
    val bbB = routerB.flush()
    routerB.receive(bbA)
    routerA.receive(bbB)
  }

  import MessageRouter._

  "MessageRouter" should "create a new channel" in {
    val routerA = defaultRouterA
    val routerB = defaultRouterB

    // create a system channel
    val systemHandler = new TestSystemHandler
    routerA.createChannel(SystemProtocol)(systemHandler, SystemChannelContext("system"), CreateSystemChannel)
    val bb = routerA.flush()
    // make a copy of the byte buffer so we can inspect its contents
    val data = bb.slice().order(ByteOrder.LITTLE_ENDIAN) // remember to set little endian on the copy

    // check that everything is correctly serialized
    val uState = new UnpickleState(new DecoderSpeed(bb), false, false)
    val startTag = uState.dec.readInt
    startTag shouldBe MessageRouter.StartTag
    uState.dec.readInt shouldBe (RouterChannelId | MessageTag)
    var msg = uState.unpickle[RouterMessage]
    msg shouldBe EstablishRoute(true)
    uState.dec.readInt shouldBe (RouterChannelId | MessageTag)
    msg = uState.unpickle[RouterMessage]
    msg shouldBe EstablishChannel(0x1000, 1, 0)
    val md = uState.unpickle[MainChannelMetadata]
    md shouldBe CreateSystemChannel
    val ctx = uState.unpickle[SystemChannelContext]
    ctx shouldBe SystemChannelContext("system")
    val endTag = uState.dec.readInt
    endTag shouldBe MessageRouter.EndTag

    // check that the other router can receive the data
    routerB.receive(data)
  }

  it should "send a message on the channel" in {
    // init routers and set up channel
    val (routerA, routerB, systemHandlerA, systemHandlerB, channel) = initRouters
    routerA.hasPending shouldBe false
    // send the message
    channel.send(System1(5))
    routerA.hasPending shouldBe true
    val bb = routerA.flush()
    val data = bb.slice().order(ByteOrder.LITTLE_ENDIAN) // remember to set little endian on the copy

    val uState = new UnpickleState(new DecoderSpeed(bb), false, false)
    val startTag = uState.dec.readInt
    startTag shouldBe MessageRouter.StartTag
    val channelId = uState.dec.readInt
    channelId shouldBe (0x1000 | MessageTag)
    val msg = uState.unpickle[Message]
    msg shouldBe System1(5)
    val endTag = uState.dec.readInt
    endTag shouldBe MessageRouter.EndTag

    routerB.receive(data)
    systemHandlerB.receivedMessages shouldBe Vector(System1(5))
  }

  it should "send a message in other direction" in {
    // init routers and set up channel
    val (routerA, routerB, systemHandlerA, systemHandlerB, channel) = initRouters
    systemHandlerB.channel.send(System1(42))
    runRouters(routerA, routerB)
    systemHandlerA.receivedMessages shouldBe Vector(System1(42))
  }

  it should "close a channel" in {
    // init routers and set up channel
    val (routerA, routerB, systemHandlerA, systemHandlerB, channel) = initRouters

    routerB.globalChannels.size shouldBe 2
    channel.close()
    runRouters(routerA, routerB)
    routerA.globalChannels(channel.globalId).state shouldBe StateClosing
    routerB.globalChannels(channel.globalId).state shouldBe StateClosed

    runRouters(routerA, routerB)

    routerA.globalChannels(channel.globalId).state shouldBe StateClosed
    routerB.globalChannels(channel.globalId).state shouldBe StateClosed
  }

  "MessageChannel" should "create a child channel" in {
    import RandomProtocol._
    // init routers and set up channel
    val (routerA, routerB, systemHandlerA, systemHandlerB, channel) = initRouters

    val randomHandler = new RandomHandler
    val randomChannel = channel.createChannel(RandomProtocol)(randomHandler, RandomProtocolContext(42), "random")
    // send a messages
    randomChannel.send(GenRandom(42))
    // allow message to be processed and response message sent and processed
    runRouters(routerA, routerB)
    runRouters(routerA, routerB)
    randomHandler.receivedValue shouldBe 9
  }
}
