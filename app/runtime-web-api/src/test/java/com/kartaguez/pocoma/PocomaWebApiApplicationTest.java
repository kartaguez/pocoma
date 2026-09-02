package com.kartaguez.pocoma;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PocomaWebApiApplicationTest {
	@Test void targetWebCompositionStartsWithTheImmutableQueryReader() {}
}
