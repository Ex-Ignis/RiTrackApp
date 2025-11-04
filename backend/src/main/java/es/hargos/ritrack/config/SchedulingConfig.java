package es.hargos.ritrack.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import java.util.concurrent.Executors;

@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        // Configurar un pool de hilos para las tareas programadas
        // Esto evita que las tareas se bloqueen entre sí
        taskRegistrar.setScheduler(Executors.newScheduledThreadPool(3));
    }
}