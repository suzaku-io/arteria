# Channels and protocols

A `MessageChannel` is a bidirectional communication channel between the routers, passing _messages_ defined by the `Protocol`. To create a
channel you need to define a _protocol_ and a _handler_ for your messages.

## Protocol

Message protocol defines what messages can be sent on a channel and how they are serialized. It also defines a context type and its
serialization, which is used when materializing a channel. To define a protocol for our logging channel, we could write something like this:

```scala
import arteria.core._
import boopickle.Default._

object LoggerProtocol extends Protocol {

  sealed trait LoggerMessage extends Message

  case class LogDebug(message: String) extends LoggerMessage

  case class LogInfo(message: String) extends LoggerMessage

  case class LogWarn(message: String) extends LoggerMessage

  case class LogError(message: String) extends LoggerMessage

  private val logPickler = compositePickler[LoggerMessage]
    .addConcreteType[LogDebug]
    .addConcreteType[LogInfo]
    .addConcreteType[LogWarn]
    .addConcreteType[LogError]

  implicit val (messagePickler, logMsgWitness) = defineProtocol(logPickler)

  case class LoggerProtocolContext(name: String)

  override type ChannelContext = LoggerProtocolContext

  override val contextPickler = implicitly[Pickler[LoggerProtocolContext]]
}
```

First we define a base trait `LoggerMessage` for all logger messages and then the actual messages. These message case classes form a sealed
class hierarchy which can be reliably serialized (pickled). Next step is to define a _composite pickler_ `logPickler` that knows how to
pickle and unpickle the logger messages. The composite pickler is defined by its base type and by adding individual concrete types with
`addConcreteType`. Then we are ready to define the actual `messagePickler` using the `defineProtocol` helper function, passing the 
previously created composite pickler. In addition to the pickler, `defineProtocol` also returns a _message witness_ which is used to ensure
that messages sent on this channel are type safe.

`MessageWitness[M, P]` is a type class that signals that a messages of type `M` are valid for protocol `P`. Calling `send` on a channel
requires that an appropriate `MessageWitness` is implicitly available. By storing the message witnesses inside the protocol class, they
automatically are.
 
When a channel is created, its initial _context_ is sent to the other side to be used in channel materialization. For this purpose we need
to override the `ChannelContext` type with our own context and provide a pickler for the context. Here we can simply use an implicitly
created pickler.

## Message handler

While Arteria makes sure that your messages are passed through, it cannot handle those messages for you, so you need to provide a
`MessageChannelHandler`. The handler trait has several lifecycle callbacks such as `establishing`, `established` and `closed`, but mainly
we are interested in the `process` method which is called for every message received.

A simple implementation for handling the logger protocol could be something like this,

```scala
class LoggerHandler(logger: Logger) extends MessageChannelHandler[LoggerProtocol.type] {
  override def process = {
    case LogDebug(msg) => logger.debug(msg)
    case LogInfo(msg)  => logger.info(msg)
    case LogWarn(msg)  => logger.warn(msg)
    case LogError(msg) => logger.error(msg)
  }
}
```

The `process` method actually returns a `PartialFunction[Message, Unit]` which handles the real message processing. This makes defining the
implementation simple, as you can just write the matching `case` statements for each message type.

## Materializing channels

When a new channel is created on top of a router (or another channel), a special message is sent to the other router, instructing it to
_materialize_ an instance of that channel. For our logger, we need to do this in our router handler.

```scala
class TopChannelHandler(loggerHandler: MessageChannelHandler[LoggerProtocol.type]) 
  extends MessageRouterHandler[RouterChild] {
  override def materializeChildChannel(id: Int,
                                       globalId: Int,
                                       router: MessageRouterBase,
                                       materializeChild: RouterChild,
                                       contextReader: ChannelReader): MessageChannelBase = {
    materializeChild match {
      case CreateLoggerChannel =>
        val context = contextReader.read[LoggerProtocol.ChannelContext]
        Some(new MessageChannel(LoggerProtocol)(id, globalId, router, loggerHandler, context))
```

First step is to read the channel context data from `ChannelReader` and pass that, with other relevant parameters including the logger
handler, to the constructor of `MessageChannel`. Once the channel is created, it's ready to process incoming messages.

## Creating a channel

Channels are always created as sub-channels of existing channels. The only exception being the _router_, which is also a channel, but stands
on its own. To create our logger channel, we need to call `createChannel` on the router.

```scala
val loggerChannel = router.createChannel(LoggerProtocol)(
  MessageChannelHandler.empty,
  LoggerProtocol.LoggerProtocolContext("MainLog"),
  CreateLogChannel)
```

The first argument to `createChannel` is the protocol implementation, which also defines the types for the next arguments. While channels
are bidirectional, in case of our logger we only send messages in one direction, so at this end we can replace the channel handler with an
empty implementation. The initial context of the channel is passed as well as the instruction to materialize the correct channel type. 

## Sending messages

Establishing the channel is an asynchronous process as the other router must acknowledge the new channel, but we can start sending messages
immediately after we have created the channel. Simply call the `send` method and pass an appropriate message:

```scala
loggerChannel.send(LogDebug("Just debugging"))
```

If you try to send a message that is not supported by the protocol, you will get a compilation error:

```scala
loggerChannel.send(UIProtocol.NextFrame(0))
>>> Message of type UIProtocol.NextFrame is not valid for protocol LoggerProtocol.type
```

To make the use of logger a bit easier, we can define a wrapper interface that provides nice and simple methods:

```scala
val logger: Logger = new Logger {
  override def error(message: String): Unit = loggerChannel.send(LogError(message))
  override def debug(message: String): Unit = loggerChannel.send(LogDebug(message))
  override def info(message: String): Unit  = loggerChannel.send(LogInfo(message))
  override def warn(message: String): Unit  = loggerChannel.send(LogWarn(message))
}
```

## Closing a channel

Once you are done with the channel, you can close it simply by calling

```scala
loggerChannel.close()
```

This will close down the channel on this end and send a request to the other router to close down the channel on the other side as well.
Your channel handler `closed()` callback is called and you can do clean-up etc. there. The parent channel handler's `channelClosed` method
is also called.

