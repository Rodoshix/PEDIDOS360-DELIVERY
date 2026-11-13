package cl.duoc.pedidos360.kafkaadmin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.*;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class AdminSafetyTests {
  final String run = "20dc8ee5-52aa-4b77-a2da-0435a9ac97a8";
  final String cluster = "test-cluster-identifier-123";
  final String topic = "p360.disposable." + run + ".sample";
  AdminRequest request(String command, String name, String config, String value, String confirm) {
    return parse(command,"localhost:29092",name,config,value,confirm,true);
  }

  AdminRequest parse(String command,String servers,String name,String config,String value,String confirm,boolean disposable) {
    return AdminRequest.parse(new MockEnvironment(),new DefaultApplicationArguments(arguments(command,servers,name,config,value,confirm,disposable).toArray(String[]::new)));
  }
  List<String> arguments(String command,String servers,String name,String config,String value,String confirm,boolean disposable) {
    var args=new ArrayList<String>(List.of("--p360.kafka.admin.command="+command,
        "--p360.kafka.bootstrap-servers="+servers,"--p360.kafka.admin.run-id="+run,
        "--p360.kafka.admin.cluster-id="+cluster,"--p360.kafka.admin.topic="+name,
        "--p360.kafka.admin.disposable="+disposable));
    if(config!=null)args.add("--p360.kafka.admin.config="+config);
    if(value!=null)args.add("--p360.kafka.admin.value="+value);
    if(confirm!=null)args.add("--p360.kafka.admin.confirm="+confirm);
    return args;
  }

  @Test void operationalAndForeignTopicsCannotBeDeleted() {
    var names = new ArrayList<>(TopicCatalog.topics().stream().map(TopicCatalog.Spec::name).toList());
    names.addAll(List.of("other", "p360.disposable."+UUID.randomUUID()+".sample", topic+".dlt", topic+"\nforged"));
    for (String name : names) {
      assertThatThrownBy(()->request("test-delete",name,null,null,"DELETE:"+name)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(()->request("test-update",name,"retention.ms","120000","UPDATE:"+name+":retention.ms:120000")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(()->request("test-reset-config",name,"retention.ms",null,"RESET:"+name+":retention.ms")).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test void explicitConfirmationAndLocalDisposableContextAreMandatory() {
    assertThatThrownBy(()->request("test-delete",topic,null,null,null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->request("test-delete",topic,null,null,"DELETE:other")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->parse("test-delete","localhost:29092",topic,null,null,"DELETE:"+topic,false)).isInstanceOf(IllegalArgumentException.class);
    for(String endpoint:List.of("localhost:19092","remote.example:29092","127.0.0.1:0","localhost:65536","localhost:29092,localhost:29093"))
      assertThatThrownBy(()->parse("test-delete",endpoint,topic,null,null,"DELETE:"+topic,true)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void configurationKeysValuesAndResetConfirmationAreRestricted() {
    for(String key:List.of("unclean.leader.election.enable","password","delete.topic.enable","retention.bytes"))
      assertThatThrownBy(()->request("test-update",topic,key,"true",null)).isInstanceOf(IllegalArgumentException.class);
    for(String value:List.of("-1","0","59999","604800001","1e6"," 60000","60000\n"))
      assertThatThrownBy(()->request("test-update",topic,"retention.ms",value,null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->request("test-update",topic,"cleanup.policy","unknown",null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->request("test-reset-config",topic,"retention.ms",null,null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(request("test-reset-config",topic,"retention.ms",null,"RESET:"+topic+":retention.ms").command()).isEqualTo("test-reset-config");
  }

  @Test void unknownRepeatedAndInapplicableParametersFailBeforeClientCreation() {
    var env = new MockEnvironment();
    for(String[] args:List.of(new String[]{"--force=true"},new String[]{"stray"},
        new String[]{"--p360.kafka.admin.command=describe","--p360.kafka.admin.command=groups"},
        new String[]{"--p360.kafka.admin.command"}))
      assertThatThrownBy(()->AdminRequest.parse(env,new DefaultApplicationArguments(args))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->AdminRequest.parse(new MockEnvironment().withProperty("p360.kafka.admin.command","delete"),new DefaultApplicationArguments())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->AdminRequest.parse(new MockEnvironment().withProperty("p360.kafka.admin.topic",topic),new DefaultApplicationArguments())).isInstanceOf(IllegalArgumentException.class);
    for(String command:List.of("list","describe","plan","apply","groups"))
      assertThat(AdminRequest.parse(new MockEnvironment().withProperty("p360.kafka.admin.command",command),new DefaultApplicationArguments("--p360.kafka.admin.command="+command)).command()).isEqualTo(command);
  }

  @Test void everyUpdateRequiresConsentBoundToOperationTopicKeyAndValue() {
    for(var setting:Map.of("retention.ms","120000","cleanup.policy","delete","segment.ms","60000").entrySet()) {
      String key=setting.getKey(),value=setting.getValue(),token="UPDATE:"+topic+":"+key+":"+value;
      assertThat(request("test-update",topic,key,value,token).confirmation()).isEqualTo(token);
      for(String wrong:List.of("DELETE:"+topic,"RESET:"+topic+":"+key,
          "UPDATE:"+topic+"-other:"+key+":"+value,"UPDATE:"+topic+":other:"+value,token+"0"))
        assertThatThrownBy(()->request("test-update",topic,key,value,wrong)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(()->request("test-update",topic,key,value,null)).isInstanceOf(IllegalArgumentException.class);
    }
    // Even an apparent retention increase requires fresh consent; no racy read-before-write classification.
    assertThat(request("test-update",topic,"retention.ms","604800000","UPDATE:"+topic+":retention.ms:604800000").value()).isEqualTo("604800000");
    for(String value:List.of("-1","0","59999","604800001","1e6"," 60000","60000\n"))
      assertThatThrownBy(()->request("test-update",topic,"retention.ms",value,"UPDATE:"+topic+":retention.ms:"+value)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->request("test-update",topic,"cleanup.policy","unknown","UPDATE:"+topic+":cleanup.policy:unknown")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->request("test-update",topic,"retention.bytes","120000","UPDATE:"+topic+":retention.bytes:120000")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->request("test-update",topic,"segment.ms","86400001","UPDATE:"+topic+":segment.ms:86400001")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->request("test-reset-config",topic,"retention.ms",null,"UPDATE:"+topic+":retention.ms:120000")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->request("test-delete",topic,null,null,"RESET:"+topic+":retention.ms")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void inheritedMutationsAreRejectedBeforeOpeningAnyAdminClient() {
    for(String command:List.of("test-create","test-update","test-reset-config","test-delete","apply")) {
      var inherited=new MockEnvironment().withProperty("p360.kafka.admin.command",command)
          .withProperty("p360.kafka.bootstrap-servers","localhost:29092")
          .withProperty("p360.kafka.admin.run-id",run).withProperty("p360.kafka.admin.cluster-id",cluster)
          .withProperty("p360.kafka.admin.topic",topic).withProperty("p360.kafka.admin.disposable","true")
          .withProperty("p360.kafka.admin.config","retention.ms").withProperty("p360.kafka.admin.value","120000")
          .withProperty("p360.kafka.admin.confirm",command.equals("test-delete")?"DELETE:"+topic:
              command.equals("test-reset-config")?"RESET:"+topic+":retention.ms":"UPDATE:"+topic+":retention.ms:120000");
      try(var factory=mockStatic(Admin.class)) {
        assertThatThrownBy(()->new KafkaAdminApplication().adminCommand(inherited).run(new DefaultApplicationArguments()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Explicit command");
        factory.verifyNoInteractions();
      }
    }
  }

  @Test void allMutationAuthorizationFieldsMustBelongToCurrentInvocation() {
    var full=arguments("test-update","localhost:29092",topic,"retention.ms","120000","UPDATE:"+topic+":retention.ms:120000",true);
    var inherited=new MockEnvironment();
    for(String arg:full) {int split=arg.indexOf('=');inherited.withProperty(arg.substring(2,split),arg.substring(split+1));}
    for(String omitted:full) {
      var incomplete=new ArrayList<>(full);incomplete.remove(omitted);
      try(var factory=mockStatic(Admin.class)) {
        assertThatThrownBy(()->new KafkaAdminApplication().adminCommand(inherited).run(new DefaultApplicationArguments(incomplete.toArray(String[]::new))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Explicit");
        factory.verifyNoInteractions();
      }
    }
    for(String command:List.of("test-update","test-reset-config","test-delete")) {
      String key=command.equals("test-delete")?null:"retention.ms";
      String value=command.equals("test-update")?"120000":null;
      String token=command.equals("test-delete")?"DELETE:"+topic:
          command.equals("test-reset-config")?"RESET:"+topic+":retention.ms":"UPDATE:"+topic+":retention.ms:120000";
      var current=arguments(command,"localhost:29092",topic,key,value,null,true);
      inherited.withProperty("p360.kafka.admin.confirm",token);
      try(var factory=mockStatic(Admin.class)) {
        assertThatThrownBy(()->new KafkaAdminApplication().adminCommand(inherited).run(new DefaultApplicationArguments(current.toArray(String[]::new))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Explicit p360.kafka.admin.confirm");
        factory.verifyNoInteractions();
      }
    }
    // Explicit values take precedence over conflicting inherited configuration.
    inherited.withProperty("p360.kafka.admin.command","test-delete").withProperty("p360.kafka.admin.confirm","DELETE:"+topic);
    assertThat(AdminRequest.parse(inherited,new DefaultApplicationArguments(full.toArray(String[]::new))).command()).isEqualTo("test-update");
  }

  @Test void readOnlyDefaultsAndInheritedQueriesRemainAvailable() {
    assertThat(AdminRequest.parse(new MockEnvironment(),new DefaultApplicationArguments()).command()).isEqualTo("describe");
    for(String command:List.of("describe","list","plan","groups"))
      assertThat(AdminRequest.parse(new MockEnvironment().withProperty("p360.kafka.admin.command",command),new DefaultApplicationArguments()).command()).isEqualTo(command);
  }

  @Test void clusterMismatchRejectsDeletionBeforeAnyMutation() throws Exception {
    var admin=mock(Admin.class);var described=mock(DescribeClusterResult.class);
    when(admin.describeCluster()).thenReturn(described);
    when(described.clusterId()).thenReturn(KafkaFuture.completedFuture("different-cluster"));
    assertThatThrownBy(()->new DisposableTopicAdmin(admin).execute(request("test-delete",topic,null,null,"DELETE:"+topic))).isInstanceOf(IllegalStateException.class);
    verify(admin,never()).deleteTopics(anyCollection());verify(admin,never()).incrementalAlterConfigs(anyMap());
  }

  @Test void missingBrokerMarkerOrDisabledDeletionCannotBeOverriddenByConfirmation() throws Exception {
    for(boolean marker:List.of(false,true)) {
      var admin=mock(Admin.class);var described=mock(DescribeClusterResult.class);
      when(admin.describeCluster()).thenReturn(described);
      when(described.clusterId()).thenReturn(KafkaFuture.completedFuture(cluster));
      when(described.nodes()).thenReturn(KafkaFuture.completedFuture(List.of(new Node(1,"localhost",9092,marker?"p360-disposable-"+run:null))));
      var resource=new ConfigResource(ConfigResource.Type.BROKER,"1");var configs=mock(DescribeConfigsResult.class);
      when(admin.describeConfigs(anyCollection())).thenReturn(configs);
      when(configs.all()).thenReturn(KafkaFuture.completedFuture(Map.of(resource,new Config(List.of(new ConfigEntry("delete.topic.enable","false"))))));
      assertThatThrownBy(()->new DisposableTopicAdmin(admin).execute(request("test-delete",topic,null,null,"DELETE:"+topic))).isInstanceOf(IllegalStateException.class);
      verify(admin,never()).deleteTopics(anyCollection());
    }
  }
}
