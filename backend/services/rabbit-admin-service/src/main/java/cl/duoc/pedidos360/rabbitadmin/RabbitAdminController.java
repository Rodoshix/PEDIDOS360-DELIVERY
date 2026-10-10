package cl.duoc.pedidos360.rabbitadmin;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static cl.duoc.pedidos360.rabbitadmin.AdminDtos.*;

@RestController
@RequestMapping("/admin/rabbit")
public class RabbitAdminController {
    private final RabbitAdminService service;
    public RabbitAdminController(RabbitAdminService service) { this.service=service; }
    @PutMapping("/queues/{name}") public QueueResult queue(@PathVariable String name,@Valid @RequestBody QueueRequest body) { return service.putQueue(name,body); }
    @GetMapping("/queues/{name}") public QueueResult queue(@PathVariable String name) { return service.getQueue(name); }
    @DeleteMapping("/queues/{name}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteQueue(@PathVariable String name) { service.deleteQueue(name); }
    @PutMapping("/exchanges/{name}") public ExchangeResult exchange(@PathVariable String name,@Valid @RequestBody ExchangeRequest body) { return service.putExchange(name,body); }
    @DeleteMapping("/exchanges/{name}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteExchange(@PathVariable String name) { service.deleteExchange(name); }
    @PostMapping("/bindings") @ResponseStatus(HttpStatus.CREATED) public BindingResult binding(@Valid @RequestBody BindingRequest body) { return service.putBinding(body); }
    @DeleteMapping("/bindings/{bindingId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteBinding(@PathVariable String bindingId) { service.deleteBinding(bindingId); }
}
