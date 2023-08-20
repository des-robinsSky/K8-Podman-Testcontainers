package uk.sky.ovp.k8podmanpinp;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.cassandra.CassandraConnectionDetails;
import org.springframework.boot.autoconfigure.cassandra.CassandraProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;

import java.net.InetSocketAddress;
import java.util.List;

@TestConfiguration(proxyBeanMethods = false)
@SpringBootApplication
public class TestK8PodmanPinPApplication {

    private static final String CASSANDRA_3_11 = "cassandra:3.11";

    @Profile("!linux") // When NOT in a K8 pod - see README
    static class NonLinuxHostNetwork {

        @Bean
        @Lazy
        @ServiceConnection
        CassandraContainer<?> cassandraContainer() {
            return buildBaseCassandraContainer();
        }
    }

    @Profile("linux") // Host driver mode - see README
    static class LinuxHostNetwork {

        private static final String ALL_BINDINGS_INTERFACE = "0.0.0.0";
        private static final String DEFAULT_CASSANDRA_PORT = "9042";
        private static final String PROC = "/proc";

        @Bean
        @Lazy
        @ServiceConnection
        CassandraContainer<?> cassandraContainerLinux() {
            var cassandraContainer = buildBaseCassandraContainer()
                .withNetworkMode("host")
                .withFileSystemBind(PROC, PROC, BindMode.READ_WRITE);

//            See README. Testcontainers hack to set the port bindings but not the exposed ports
            cassandraContainer.setPortBindings(List.of(DEFAULT_CASSANDRA_PORT + "/tcp"));
            cassandraContainer.setExposedPorts(List.of());

            return cassandraContainer;
        }

        @Bean
        CassandraConnectionDetails cassandraConnectionDetails(CassandraProperties cassandraProperties) {
            return new CassandraConnectionDetails() {
                @Override
                public List<Node> getContactPoints() {
                    return List.of(new Node(ALL_BINDINGS_INTERFACE, cassandraProperties.getPort()));
                }

                @Override
                public String getLocalDatacenter() {
                    return cassandraProperties.getLocalDatacenter();
                }
            };
        }

        @Bean
        @Lazy
        @Primary
        CassandraProperties cassandraProperties(CassandraContainer<?> cassandraContainer) {
            CassandraProperties cassandraProperties = new CassandraProperties();
            cassandraProperties.setCompression(CassandraProperties.Compression.NONE);
            cassandraProperties.setLocalDatacenter(cassandraContainer.getLocalDatacenter());
            cassandraProperties.setContactPoints(List.of(ALL_BINDINGS_INTERFACE + ":" + DEFAULT_CASSANDRA_PORT));
            cassandraProperties.setUsername(cassandraContainer.getUsername());
            cassandraProperties.setPassword(cassandraContainer.getPassword());
            return cassandraProperties;
        }

        @Bean
        @Primary
        @Lazy
        public CqlSession cassandraSession(CassandraProperties cassandraProperties) {
            return new CqlSessionBuilder()
                .withLocalDatacenter(cassandraProperties.getLocalDatacenter())
                .addContactPoint(new InetSocketAddress(ALL_BINDINGS_INTERFACE, cassandraProperties.getPort()))
                .build();
        }

    }

    private static CassandraContainer<?> buildBaseCassandraContainer() {
        return new CassandraContainer<>(CASSANDRA_3_11)
            .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
            .waitingFor(new LogMessageWaitStrategy().withRegEx(".*Startup complete.*"));
    }

    public static void main(String[] args) {
        SpringApplication.run(TestK8PodmanPinPApplication.class);
    }
}
