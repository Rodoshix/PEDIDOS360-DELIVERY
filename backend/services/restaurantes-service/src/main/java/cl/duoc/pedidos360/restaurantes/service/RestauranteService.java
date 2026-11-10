package cl.duoc.pedidos360.restaurantes.service;

import cl.duoc.pedidos360.restaurantes.dto.RestauranteDto;
import cl.duoc.pedidos360.restaurantes.entity.EstadoRestaurante;
import cl.duoc.pedidos360.restaurantes.entity.Restaurante;
import cl.duoc.pedidos360.restaurantes.exception.RestauranteNoEncontradoException;
import cl.duoc.pedidos360.restaurantes.repository.RestauranteRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import cl.duoc.pedidos360.kafka.EventCodec;
import cl.duoc.pedidos360.kafka.KafkaStores;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class RestauranteService {

    private final RestauranteRepository restauranteRepository;

    private KafkaStores kafka;
    @Autowired
    void configureKafka(ObjectProvider<KafkaStores> stores) {
        kafka = stores.getIfAvailable();
    }

    public RestauranteService(RestauranteRepository restauranteRepository) {
        this.restauranteRepository = restauranteRepository;
    }

    @Transactional(readOnly = true)
    public List<RestauranteDto> listar() {
        return restauranteRepository.findAll()
                .stream()
                .map(this::convertirADto)
                .toList();
    }

    @Transactional(readOnly = true)
    public RestauranteDto obtenerPorId(Long id) {
        Restaurante restaurante = buscarEntidadPorId(id);
        return convertirADto(restaurante);
    }

    @Transactional
    public RestauranteDto crear(RestauranteDto dto) {
        Restaurante restaurante = new Restaurante(
                dto.getNombre(),
                dto.getDescripcion(),
                dto.getDireccion(),
                dto.getEstado()
        );

        Restaurante guardado = restauranteRepository.save(restaurante);

        if (kafka != null) {
            kafka.record(EventCodec.RESTAURANTES, guardado.getId(), guardado.getEstado().name(), false);
        }
        return convertirADto(guardado);
    }

    @Transactional
    public RestauranteDto actualizar(Long id, RestauranteDto dto) {
        Restaurante restaurante = buscarParaModificar(id);

        restaurante.setNombre(dto.getNombre());
        restaurante.setDescripcion(dto.getDescripcion());
        restaurante.setDireccion(dto.getDireccion());
        restaurante.setEstado(dto.getEstado());

        Restaurante actualizado = restauranteRepository.save(restaurante);

        if (kafka != null) {
            kafka.record(EventCodec.RESTAURANTES, actualizado.getId(), actualizado.getEstado().name(), false);
        }
        return convertirADto(actualizado);
    }

    @Transactional
    public void desactivar(Long id) {
        Restaurante restaurante = buscarParaModificar(id);

        restaurante.setEstado(EstadoRestaurante.INACTIVO);

        restauranteRepository.save(restaurante);
        if (kafka != null) {
            kafka.record(EventCodec.RESTAURANTES, restaurante.getId(), restaurante.getEstado().name(), false);
        }
    }

    private Restaurante buscarParaModificar(Long id) {
        if (kafka == null) return buscarEntidadPorId(id);
        return restauranteRepository.findForUpdate(id)
                .orElseThrow(() -> new RestauranteNoEncontradoException(id));
    }

    private Restaurante buscarEntidadPorId(Long id) {
        return restauranteRepository.findById(id)
                .orElseThrow(() -> new RestauranteNoEncontradoException(id));
    }

    private RestauranteDto convertirADto(Restaurante restaurante) {
        return new RestauranteDto(
                restaurante.getId(),
                restaurante.getNombre(),
                restaurante.getDescripcion(),
                restaurante.getDireccion(),
                restaurante.getEstado()
        );
    }
}