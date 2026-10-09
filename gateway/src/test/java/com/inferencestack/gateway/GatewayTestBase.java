package com.inferencestack.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real Spring context + real PostgreSQL + FakeEngine in place of the C++ engine. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class GatewayTestBase {
    static final FakeEngine ENGINE = new FakeEngine();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        ENGINE.reset();
        jdbc.update("TRUNCATE request_log");
    }

    /** The telemetry insert happens just before the response is released; poll briefly to be safe. */
    List<Map<String, Object>> rows(int expected) throws Exception {
        for (int i = 0; i < 40; i++) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM request_log ORDER BY id");
            if (rows.size() >= expected) return rows;
            Thread.sleep(50);
        }
        assertThat(jdbc.queryForList("SELECT * FROM request_log")).hasSize(expected);
        return List.of();
    }
}
