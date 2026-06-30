package org.example.demo.payment;

import lombok.Getter;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("mysql-it")
@TestMethodOrder(OrderAnnotation.class)
class DepositTableWritePerformanceMySqlIT extends AbstractDepositTableWritePerformanceTest {

    @Getter
    private final String dbType = "mysql";
}
