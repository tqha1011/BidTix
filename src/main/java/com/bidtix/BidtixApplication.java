package com.bidtix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BidtixApplication {

	public static void main(String[] args) {
		SpringApplication.run(BidtixApplication.class, args);
	}

}
