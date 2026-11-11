package com.bidtix;

import com.bidtix.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

/** Boots the whole application against a real PostgreSQL: Flyway migrations, JPA mappings and all queries. */
class BidtixBackendApplicationTests extends PostgresIntegrationTest {

	@Test
	void contextLoads() {
	}

}
