package cl.duoc.pedidos360.usuarios.messaging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cl.duoc.pedidos360.messaging.fixture.*;
import cl.duoc.pedidos360.messaging.actor.*;
import cl.duoc.pedidos360.messaging.identity.*;
import cl.duoc.pedidos360.messaging.envelope.*;
import cl.duoc.pedidos360.usuarios.entity.Usuario;
import cl.duoc.pedidos360.usuarios.repository.UsuarioRepository;
import cl.duoc.pedidos360.usuarios.security.*;
import cl.duoc.pedidos360.usuarios.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class UsuariosIdentityProofTests {
    final UUID tenant=UUID.randomUUID(), oid=UUID.randomUUID();
    final Instant now=IdentityProofCodec.millis(Instant.now());
    final JsonMapper json=JsonMapper.builder().build();
    Usuario profile() {
        var u=new Usuario(tenant,oid,"Ana","Perez","ana@example.test",null);
        ReflectionTestUtils.setField(u,"id",42L); return u;
    }
    IdentidadUsuario identity() { return new IdentidadUsuario(tenant,oid,Set.of(IdentidadUsuario.Rol.CLIENTE)); }
    ActorContext actor() { return new ActorContext(tenant,oid,Set.of("CLIENTE"),Set.of("access_as_user"),now,now.plusSeconds(4),"p360.usuarios.consultas.q",FixtureActorKeys.ID); }
    RequestEnvelope request() { return RequestEnvelope.crear(UUID.randomUUID(),"usuario.consultar-actual.v1",json.createObjectNode(),"verified-by-listener",now,now.plusSeconds(5)); }
    UsuariosIdentityProofSigner signer() { return new UsuariosIdentityProofSigner(new SigningKey(FixtureIdentityKeys.ID,FixtureIdentityKeys.KEY),FixtureIdentityKeys.keys(),Clock.systemUTC(),Duration.ofMillis(250)); }
    MockEnvironment environment() { return new MockEnvironment()
            .withProperty("pedidos360.messaging.role","SERVICE")
            .withProperty("pedidos360.messaging.actor.public-jwks",FixtureActorKeys.publicJwks())
            .withProperty("pedidos360.messaging.identity-proof.public-jwks",FixtureIdentityKeys.publicJwks())
            .withProperty("pedidos360.messaging.identity-proof.private-jwk",FixtureIdentityKeys.KEY.toJSONString())
            .withProperty("pedidos360.messaging.identity-proof.key-id",FixtureIdentityKeys.ID.toString()); }
    @Test void singlePersistentReadProducesHttpProjectionAndSignedLocalIdentity() {
        var repo=mock(UsuarioRepository.class); var u=profile(); when(repo.findByTenantIdAndEntraObjectId(tenant,oid)).thenReturn(Optional.of(u));
        var service=new UsuarioService(repo,mock(IdentidadActual.class)); var req=request();
        var payload=new UsuariosQueryProcessor(service,json,signer()).procesar(actor(),req);
        verify(repo,times(1)).findByTenantIdAndEntraObjectId(tenant,oid); verifyNoMoreInteractions(repo);
        var proof=new IdentityProofVerifier(FixtureIdentityKeys.keys(),Clock.systemUTC(),Duration.ofMillis(250))
                .verify(payload.path("pruebaIdentidad").stringValue(),tenant,oid,req.messageId(),req.expiresAt());
        assertThat(proof.usuarioId()).isEqualTo(42);
        assertThat(proof.perfilVerificadoEn()).isBetween(now,Instant.now());
        var projection=(tools.jackson.databind.node.ObjectNode)payload.deepCopy(); projection.remove("pruebaIdentidad");
        assertThat(projection).isEqualTo(json.valueToTree(cl.duoc.pedidos360.usuarios.dto.UsuarioResponse.desde(u)));
        assertThat(service.obtenerActual(identity()).id()).isEqualTo(42);
        verify(repo,times(2)).findByTenantIdAndEntraObjectId(tenant,oid); // Una lectura por invocación HTTP/Rabbit.
    }
    @Test void noProofForMissingOrInactiveProfileAndNoRequestSuppliedIdentity() {
        var repo=mock(UsuarioRepository.class); when(repo.findByTenantIdAndEntraObjectId(tenant,oid)).thenReturn(Optional.empty());
        var processor=new UsuariosQueryProcessor(new UsuarioService(repo,mock(IdentidadActual.class)),json,signer()); var req=request();
        assertThatThrownBy(()->processor.procesar(actor(),req)).isInstanceOf(QueryBusinessException.class).extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        var u=profile(); u.desactivar(); when(repo.findByTenantIdAndEntraObjectId(tenant,oid)).thenReturn(Optional.of(u));
        assertThatThrownBy(()->processor.procesar(actor(),req)).isInstanceOf(QueryBusinessException.class).extracting("status").isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        var supplied=RequestEnvelope.crear(req.messageId(),req.operacion(),json.createObjectNode().put("usuarioId",999),req.actor(),req.occurredAt(),req.expiresAt());
        clearInvocations(repo);
        assertThatThrownBy(()->processor.procesar(actor(),supplied)).isInstanceOf(EnvelopeException.class); verifyNoInteractions(repo);
    }
    @Test void snapshotDoesNotReReadOrPromiseImmediateRevocation() {
        var repo=mock(UsuarioRepository.class); var u=profile(); when(repo.findByTenantIdAndEntraObjectId(tenant,oid)).thenReturn(Optional.of(u));
        var snapshot=new UsuarioService(repo,mock(IdentidadActual.class)).resolverActual(identity());
        u.desactivar(); var req=request(); String proof=signer().emitir(actor(),req,snapshot);
        assertThat(new IdentityProofVerifier(FixtureIdentityKeys.keys(),Clock.systemUTC(),Duration.ofMillis(250))
                .verify(proof,tenant,oid,req.messageId(),req.expiresAt()).usuarioId()).isEqualTo(42);
        verify(repo,times(1)).findByTenantIdAndEntraObjectId(tenant,oid);
    }
    @Test void expiryClipsToOriginalDeadlineAndSlowReadCannotRenewFreshness() {
        var p=new PerfilActualVerificado(tenant,oid,cl.duoc.pedidos360.usuarios.dto.UsuarioResponse.desde(profile()),now.minusSeconds(4));
        assertThatThrownBy(()->signer().emitir(actor(),request(),p)).extracting("reason").isEqualTo(IdentityProofException.Reason.VENCIDA);
        var req=RequestEnvelope.crear(UUID.randomUUID(),"usuario.consultar-actual.v1",json.createObjectNode(),"actor",now,now.plusSeconds(1));
        var fresh=new PerfilActualVerificado(tenant,oid,p.perfil(),now);
        var proof=new IdentityProofVerifier(FixtureIdentityKeys.keys(),Clock.systemUTC(),Duration.ofMillis(250))
                .verify(signer().emitir(actor(),req,fresh),tenant,oid,req.messageId(),req.expiresAt());
        assertThat(proof.expiraEn()).isEqualTo(req.expiresAt());
    }
    @Test void configurationFailsClosedAndUsersNeverBecomesActorIssuer() {
        assertThat(UsuariosIdentityProofConfiguration.signer(environment())).isNotNull();
        assertThatThrownBy(()->UsuariosIdentityProofConfiguration.signer(environment().withProperty("pedidos360.messaging.role","BFF"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->UsuariosIdentityProofConfiguration.signer(environment().withProperty("pedidos360.messaging.identity-proof.private-jwk",""))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->UsuariosIdentityProofConfiguration.signer(environment().withProperty("pedidos360.messaging.identity-proof.key-id",UUID.randomUUID().toString()))).isInstanceOf(IllegalStateException.class);
        var actorProvider=cl.duoc.pedidos360.messaging.QueryMessagingConfiguration.proveedorDeClave(environment());
        assertThat(actorProvider.claveParaFirmar()).isEmpty();
    }
    @Test void disabledRelayAndDisabledProofDoNotLoadKeys() {
        new ApplicationContextRunner().withUserConfiguration(UsuariosIdentityProofConfiguration.class)
                .withPropertyValues("pedidos360.messaging.relay-mode=DISABLED","pedidos360.messaging.identity-proof.enabled=true")
                .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(UsuariosIdentityProofSigner.class));
        new ApplicationContextRunner().withUserConfiguration(UsuariosIdentityProofConfiguration.class)
                .withPropertyValues("pedidos360.messaging.relay-mode=ACTIVE","pedidos360.messaging.identity-proof.enabled=false")
                .run(c->assertThat(c).hasNotFailed().doesNotHaveBean(UsuariosIdentityProofSigner.class));
        new ApplicationContextRunner().withUserConfiguration(UsuariosIdentityProofConfiguration.class)
                .withPropertyValues("pedidos360.messaging.relay-mode=ACTIVE","pedidos360.messaging.identity-proof.enabled=true")
                .run(c->assertThat(c).hasFailed());
    }
}
