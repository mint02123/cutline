package com.cutline;

import com.cutline.support.MySqlTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(MySqlTestContainer.class)
class CutlineApplicationTests {

    @Test
    void contextLoads() {
    }
}
