package es.hargos.ritrack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

//Excluyo la base de datos temporalmente @SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableJpaRepositories(basePackages = "es.hargos.ritrack.repository")
@EntityScan(basePackages = "es.hargos.ritrack.entity")
@SpringBootApplication()
public class RitrackApplication {

	public static void main(String[] args) {
		SpringApplication.run(RitrackApplication.class, args);
	}

}
