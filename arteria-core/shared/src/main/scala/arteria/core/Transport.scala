package arteria.core

trait Transport {
  def subscribe(handler: MessageChannelBase => Unit): Unit

  def send(messages: MessageChannelBase): Unit
}
