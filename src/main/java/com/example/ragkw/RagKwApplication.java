package com.example.ragkw;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ComponentScan(basePackages = {"com.example.ragkw", "com.example.esrag"})
@MapperScan("com.example.esrag.mapper")
@EnableElasticsearchRepositories(basePackages = "com.example.esrag")
@EnableAsync
public class RagKwApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagKwApplication.class, args);
    }

}
