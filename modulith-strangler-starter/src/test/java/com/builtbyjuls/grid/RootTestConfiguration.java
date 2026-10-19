package com.builtbyjuls.grid;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RootTestConfiguration {

    @Bean
    public String rootBean() {
        return "root-service";
    }
}
