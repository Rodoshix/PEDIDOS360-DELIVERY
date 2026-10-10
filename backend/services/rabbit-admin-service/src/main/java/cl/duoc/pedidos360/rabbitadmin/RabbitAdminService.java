package cl.duoc.pedidos360.rabbitadmin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;
import com.rabbitmq.client.ShutdownSignalException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
import static cl.duoc.pedidos360.rabbitadmin.AdminDtos.*;

@Service
public class RabbitAdminService {
    private final RabbitAdmin admin;
    public RabbitAdminService(RabbitAdmin admin) { this.admin=admin; }
    public QueueResult putQueue(String name, QueueRequest request) {
        SandboxRules.name(name);
        if (request==null || !Boolean.TRUE.equals(request.durable())) throw AdminFailure.validation();
        return call("PUT_QUEUE",name,() -> {
            admin.declareQueue(new Queue(name,true,false,false,Map.of("x-queue-type","classic")));
            return readQueue(name);
        });
    }
    public QueueResult getQueue(String name) {
        SandboxRules.name(name);
        return call("GET_QUEUE",name,() -> readQueue(name));
    }
    private QueueResult readQueue(String name) {
        // RabbitAdmin.getQueueInfo suppresses failures as null. Preserve broker error codes instead.
        return admin.getRabbitTemplate().execute(c -> {
            var q=c.queueDeclarePassive(name);
            return new QueueResult(q.getQueue(),q.getMessageCount(),q.getConsumerCount());
        });
    }
    public void deleteQueue(String name) {
        SandboxRules.name(name);
        // Fixed policy: counters and conditional queue.delete do not protect pending ACKs.
        call("DELETE_QUEUE",name,() -> { throw AdminFailure.queueDeletionDisabled(); });
    }
    public ExchangeResult putExchange(String name, ExchangeRequest request) {
        SandboxRules.name(name);
        if (request==null || !Boolean.TRUE.equals(request.durable()) || request.type()==null) throw AdminFailure.validation();
        Exchange exchange=switch(request.type()) {
            case "direct" -> new DirectExchange(name,true,false);
            case "topic" -> new TopicExchange(name,true,false);
            case "fanout" -> new FanoutExchange(name,true,false);
            default -> throw AdminFailure.validation();
        };
        return call("PUT_EXCHANGE",name,() -> { admin.declareExchange(exchange); return new ExchangeResult(name,request.type(),true); });
    }
    public void deleteExchange(String name) {
        SandboxRules.name(name);
        // The library's boolean deleteExchange suppresses IOException; do not report a false success.
        call("DELETE_EXCHANGE",name,() -> admin.getRabbitTemplate().execute(c -> { c.exchangeDelete(name,true); return null; }));
    }
    public BindingResult putBinding(BindingRequest request) {
        validate(request);
        return call("PUT_BINDING",request.exchange(),() -> {
            admin.declareBinding(binding(request));
            return new BindingResult(encode(request));
        });
    }
    public void deleteBinding(String id) {
        BindingRequest request=decode(id); validate(request);
        call("DELETE_BINDING",request.exchange(),() -> { admin.removeBinding(binding(request)); return null; });
    }
    private static void validate(BindingRequest request) {
        if (request==null) throw AdminFailure.validation();
        SandboxRules.name(request.queue()); SandboxRules.name(request.exchange()); SandboxRules.key(request.routingKey());
    }
    private static Binding binding(BindingRequest r) { return new Binding(r.queue(),Binding.DestinationType.QUEUE,r.exchange(),r.routingKey(),null); }
    private static String encode(BindingRequest r) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((r.queue()+"\n"+r.exchange()+"\n"+r.routingKey()).getBytes(StandardCharsets.UTF_8));
    }
    private static BindingRequest decode(String id) {
        try {
            if (id==null || !id.matches("[A-Za-z0-9_-]{1,500}")) throw AdminFailure.validation();
            var parts=new String(Base64.getUrlDecoder().decode(id),StandardCharsets.UTF_8).split("\n",-1);
            if (parts.length!=3) throw AdminFailure.validation();
            var r=new BindingRequest(parts[0],parts[1],parts[2]); validate(r);
            if (!encode(r).equals(id)) throw AdminFailure.validation();
            return r;
        } catch (IllegalArgumentException invalid) { throw AdminFailure.validation(); }
    }
    private <T> T call(String operation,String resource,Supplier<T> work) {
        String outcome="OK";
        try { return work.get(); }
        catch (AdminFailure failure) {
            outcome=failure.status().name(); throw failure;
        }
        catch (AmqpException failure) {
            AdminFailure safe=translate(failure); outcome=safe.status().name(); throw safe;
        } catch (RuntimeException failure) {
            outcome="INTERNAL_ERROR"; throw failure;
        } finally { LoggerFactory.getLogger(getClass()).info("RabbitAdmin requestId={} actorHash={} operation={} resource={} outcome={}",
            org.slf4j.MDC.get("rabbitAdminRequestId"),actorHash(),operation,resource,outcome); }
    }
    private static String actorHash() {
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt && auth.isAuthenticated()) {
            try {
                String actor=jwt.getToken().getClaimAsString("tid")+":"+jwt.getToken().getClaimAsString("oid");
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(actor.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        return "unavailable";
    }
    private static AdminFailure translate(Throwable error) {
        for (Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if (cause instanceof ShutdownSignalException s) {
                if (s.getReason() instanceof com.rabbitmq.client.AMQP.Channel.Close close
                    && java.util.Set.of(403,404,405,406,540).contains(close.getReplyCode())) return AdminFailure.conflict();
                // NOT_IMPLEMENTED is a definitive protocol rejection (e.g. conditional quorum deletion).
                if (s.getReason() instanceof com.rabbitmq.client.AMQP.Connection.Close close
                    && close.getReplyCode()==540) return AdminFailure.conflict();
            }
        }
        return AdminFailure.unavailable();
    }
}
