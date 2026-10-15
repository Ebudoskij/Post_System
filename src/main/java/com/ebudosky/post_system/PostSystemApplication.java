package com.ebudosky.post_system;

import com.ebudosky.post_system.service.ShipmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PostSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(PostSystemApplication.class, args);
	}
}
