package cl.duoc.pedidos360.pedidos.messaging;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.slf4j.LoggerFactory;
/** Stop outside listener thread; closing consumer channel restores unsettled deliveries. */
public class ConfirmacionConsumerRecovery implements AutoCloseable {
 public static final String LISTENER_ID="pedidoConfirmacionListener";
 private final RabbitListenerEndpointRegistry registry;
 private final ConfirmacionReliabilityProperties properties;
 private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"confirmacion-recovery"); t.setDaemon(true); return t;});
 private final AtomicBoolean recovering=new AtomicBoolean();
 private volatile boolean closed;
 public ConfirmacionConsumerRecovery(RabbitListenerEndpointRegistry registry,ConfirmacionReliabilityProperties properties) {this.registry=registry; this.properties=properties;}
 public void recover() {
  if(closed || !recovering.compareAndSet(false,true)) return;
  executor.execute(()->{
   var container=registry.getListenerContainer(LISTENER_ID);
   if(container==null) { recovering.set(false); return; }
   try {
    container.stop(()->executor.schedule(()->restart(container),properties.recoveryBackoff().toMillis(),TimeUnit.MILLISECONDS));
   } catch(RuntimeException failure) {
    LoggerFactory.getLogger(getClass()).error("Recovery stop failed class={}",failure.getClass().getSimpleName());
    recovering.set(false);
    if(!closed) executor.schedule(this::recover,properties.recoveryBackoff().toMillis(),TimeUnit.MILLISECONDS);
   }
  });
 }
 private void restart(org.springframework.amqp.core.MessageListenerContainer container) {
  if(closed) return;
  try { container.start(); recovering.set(false); }
  catch(RuntimeException failure) {
   LoggerFactory.getLogger(getClass()).error("Recovery restart failed class={}",failure.getClass().getSimpleName());
   executor.schedule(()->restart(container),properties.recoveryBackoff().toMillis(),TimeUnit.MILLISECONDS);
  }
 }
 @Override public void close() {closed=true; executor.shutdownNow();}
}
