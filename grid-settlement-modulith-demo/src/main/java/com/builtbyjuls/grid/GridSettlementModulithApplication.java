package com.builtbyjuls.grid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.builtbyjuls.strangler.annotation.EnableModulithBridge;

@SpringBootApplication
@EnableModulithBridge
public class GridSettlementModulithApplication {

    public static void main(String[] args) {
        SpringApplication.run(GridSettlementModulithApplication.class, args);
    }
}
