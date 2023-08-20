package uk.sky.ovp.k8podmanpinp;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.CassandraContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class K8PodmanPinPApplicationTests {
    private static final String BASIC_QUERY = "SELECT release_version FROM system.local";

    @Autowired
    private CassandraContainer<?> cassandraContainer;

    @Autowired
    private CqlSession session;

    @BeforeEach
    void setup() {
        if (!cassandraContainer.isRunning()) {
            cassandraContainer.start();
        }
    }

    @AfterEach
    void tearDown() {
        cassandraContainer.stop();
    }

    @Test
    void cassandraLoad() {
        ResultSet result = session.execute(BASIC_QUERY);
        assertThat(result.wasApplied()).as("Query was applied").isTrue();
    }
}
