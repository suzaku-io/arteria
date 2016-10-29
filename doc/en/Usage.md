# Usage

## Creating routers

The first step in using Arteria is to create the primary and secondary routers. Regardless of calling them with such names, both routers are actually equal
and perform identically. The only difference is in the channel identity numbering scheme to avoid conflicts. Before a router can be instantiated, we need to
provide a `MessageRouterHandler` for it. The router handler takes care of application specific activity such as instantiating new channels under the router.
 
The `MessageRouterHandler[MaterializeChild]` takes a type parameter describing the type to deliver channel materialization information to the counterpart
router. Typically this is represented as a sealed hierarchy of case classes where each class corresponds to a type of channel that can be created. In this
documentation we will be using two top level channels, _system_ and _UI_, so our type hierarchy looks like this:

```scala
sealed abstract class RouterChild

case object CreateSystemChannel extends RouterChild
case object CreateUIChannel extends RouterChild
```

Next we'll define the handler with a minimal implementation.

```scala
import arteria.core._

class TopChannelHandler extends MessageRouterHandler[RouterChild] {
  override def materializeChildChannel(id: Int, globalId: Int, 
    router: MessageRouterBase, materializeChild: RouterChild, 
    contextReader: ChannelReader): MessageChannelBase = {
    materializeChild match {
      case CreateSystemChannel => ??? // todo
      case CreateUIChannel => ??? // todo
    }
  }
}
```

Now we are ready to instantiate the router.

```scala
val handlerPri = new TopChannelHandler
val routerPri = new MessageRouter(handlerPri, isPrimary = true) 
```

On the other side of the fence we'll do the same for the secondary router

```scala
val handlerSec = new TopChannelHandler
val routerSec = new MessageRouter(handlerSec, isPrimary = false) 
```

## Transport

As stated in the introduction, Arteria core doesn't provide any transport mechanism of its own, the routers simply produce and consume `ByteBuffer`s. In this
simplified example we just pass these buffers directly from one router to the other. To get a stream of pending messages, call the `flush` method and to pass
this stream to the other router, use the `receive` method.

```scala
val dataPri = routerPri.flush()
routerSec.receive(dataPri)
val dataSec = routerSec.flush()
routerPri.receive(dataSec)
```

The first thing routers do is to send an `EstablishRoute` message to the other router, establishing the channel between them and making sure they agree on who's
the primary router.

The transport implementation can check for pending messages by calling router's `hasPending` method before calling `flush`. Even when there are no pending
messages, a call to `flush` will produce a valid `ByteBuffer` that can be sent to the other router (albeit containing no messages). Alternatively the
application can listen to the `messagesPending` callback in the router handler, which is called after a message has been queued to be flushed. In typical usage
scenarios it makes sense to periodically check for pending messages either by having a constant timer running or by starting a (short) timer after a callback to
`messagesPending` is received. This improves performance as messages sent in a short period of time are kept together and flushed to the other router as a
single stream.

## Channels and Protocols

