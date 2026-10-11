package cl.duoc.pedidos360.pedidos.messaging;
import com.rabbitmq.client.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** One delivery per operator-approved replay; never scheduled or exposed as endpoint. */
public final class ConfirmacionDlqReplay {
 private ConfirmacionDlqReplay() {}
 public static void replay(Channel channel,GetResponse delivery,String exchange,String routingKey,long timeoutMs) throws Exception {
  String id=delivery.getProps().getMessageId();
  if(id==null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid messageId: repair required, replay refused");
  var headers=new HashMap<String,Object>();
  if(delivery.getProps().getHeaders()!=null) headers.putAll(delivery.getProps().getHeaders());
  headers.remove("x-delivery-count");
  headers.put("replay-previous-retry-count",headers.getOrDefault("retry-count",0));
  headers.put("retry-count",0); headers.put("replay-id",UUID.randomUUID().toString()); headers.put("replayed-at",Instant.now().toString());
  var properties=delivery.getProps().builder().headers(headers).deliveryMode(2).build();
  var returned=new AtomicBoolean();
  ReturnListener listener=(code,text,ex,key,p,body)->returned.set(true);
  channel.addReturnListener(listener);
  try {
   channel.confirmSelect();
   channel.basicPublish(exchange,routingKey,true,properties,delivery.getBody());
   channel.waitForConfirmsOrDie(timeoutMs);
   if(returned.get()) throw new IllegalStateException("Replay returned; original must remain unsettled");
   channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);
  } finally {channel.removeReturnListener(listener);}
 }
 public static void main(String[] args) {
  try {run(args);}
  catch(Exception failure) {System.err.println("Manual replay aborted class="+failure.getClass().getSimpleName()); System.exit(2);}
 }
 private static void run(String[] args) throws Exception {
  if(args.length!=3) throw new IllegalArgumentException("Arguments: dlq exchange routingKey; copy from pedidos360.messaging config");
  var factory=new com.rabbitmq.client.ConnectionFactory();
  try {factory.setUri(Objects.requireNonNull(System.getenv("RABBITMQ_REPLAY_URI")));}
  catch(Exception invalid) {throw new IllegalArgumentException("Set valid RABBITMQ_REPLAY_URI privately");}
  factory.setAutomaticRecoveryEnabled(false); factory.setConnectionTimeout(3000);
  try(var connection=factory.newConnection();var channel=connection.createChannel()) {
   var delivery=channel.basicGet(args[0],false);
   if(delivery==null) {System.out.println("DLQ empty");return;}
   String id=delivery.getProps().getMessageId();
   System.out.println("Unsettled messageId="+id+". Inspect DLQ metadata and real Pedido; correct cause first.");
   System.out.println("Enter REPLAY "+id+" only after verifying cause and Pedido. Any other input preserves original.");
   var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
   if(!("REPLAY "+id).equals(input.readLine())) return;
   try { replay(channel,delivery,args[1],args[2],3000); System.out.println("Publication confirmed without return; DLQ ACK sent. This does not mean Pedido confirmed."); }
   catch(Exception failure) {System.err.println("Replay failed class="+failure.getClass().getSimpleName()+"; closing channel retains unsettled DLQ original."); throw new IllegalStateException("Replay not completed");}
  }
 }
}
