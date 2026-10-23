package com.builtbyjuls.strangler.contract;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.NamedInterface;

import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.slice.ModuleLayoutInspector;

import jakarta.persistence.Entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleContractValidatorTest {

    private ModuleLayoutInspector inspector;
    private ModulithBridgeProperties properties;
    private ModuleContractValidator validator;

    @BeforeEach
    void setUp() {
        inspector = new ModuleLayoutInspector();
        properties = new ModulithBridgeProperties();
        properties.setBasePackage("com.builtbyjuls.strangler.contract");
        validator = new ModuleContractValidator(null, inspector, properties);
    }

    @Test
    @DisplayName("Valid module interface with primitives, records, BigDecimal, LocalDate, and List passes")
    void shouldPassValidModuleInterface() {
        assertThatCode(() -> validator.validateInterface(ValidModuleApi.class))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Method returning JPA entity directly throws InvalidModuleContractException")
    void shouldRejectMethodReturningJpaEntity() {
        assertThatThrownBy(() -> validator.validateInterface(ApiReturningEntity.class))
                .isInstanceOf(InvalidModuleContractException.class)
                .hasMessageContaining(ApiReturningEntity.class.getName())
                .hasMessageContaining("getEntity")
                .hasMessageContaining(SampleEntity.class.getName());
    }

    @Test
    @DisplayName("Method accepting JPA entity parameter throws InvalidModuleContractException")
    void shouldRejectMethodAcceptingJpaEntity() {
        assertThatThrownBy(() -> validator.validateInterface(ApiAcceptingEntity.class))
                .isInstanceOf(InvalidModuleContractException.class)
                .hasMessageContaining(ApiAcceptingEntity.class.getName())
                .hasMessageContaining("saveEntity")
                .hasMessageContaining(SampleEntity.class.getName());
    }

    @Test
    @DisplayName("Method returning generic collection of JPA entities throws InvalidModuleContractException")
    void shouldRejectMethodReturningListOfEntities() {
        assertThatThrownBy(() -> validator.validateInterface(ApiReturningListOfEntities.class))
                .isInstanceOf(InvalidModuleContractException.class)
                .hasMessageContaining(ApiReturningListOfEntities.class.getName())
                .hasMessageContaining("getEntities")
                .hasMessageContaining(SampleEntity.class.getName());
    }

    @Test
    @DisplayName("Method accepting or returning InputStream throws InvalidModuleContractException")
    void shouldRejectMethodWithInputStream() {
        assertThatThrownBy(() -> validator.validateInterface(ApiWithInputStream.class))
                .isInstanceOf(InvalidModuleContractException.class)
                .hasMessageContaining(ApiWithInputStream.class.getName())
                .hasMessageContaining("readStream")
                .hasMessageContaining(InputStream.class.getName());
    }

    @Test
    @DisplayName("Method accepting or returning Stream throws InvalidModuleContractException")
    void shouldRejectMethodWithStream() {
        assertThatThrownBy(() -> validator.validateInterface(ApiWithStream.class))
                .isInstanceOf(InvalidModuleContractException.class)
                .hasMessageContaining(ApiWithStream.class.getName())
                .hasMessageContaining("streamItems")
                .hasMessageContaining(Stream.class.getName());
    }

    @Test
    @DisplayName("Startup validation runs and fails context startup when invalid contract bean exists")
    void shouldFailApplicationStartupWhenContractInvalid() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(InvalidApiConfig.class);

        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(InvalidModuleContractException.class);
        });
    }

    @Test
    @DisplayName("Startup validation is skipped when validateContracts=false")
    void shouldSkipValidationWhenDisabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withPropertyValues("modulith.bridge.validate-contracts=false")
                .withUserConfiguration(InvalidApiConfig.class);

        runner.run(context -> {
            assertThat(context).hasNotFailed();
        });
    }

    // Sample interfaces, records, entities, and configurations for tests

    public record SampleDto(String code, BigDecimal amount, LocalDate date) {}

    @NamedInterface("ValidModuleApi")
    public interface ValidModuleApi {
        SampleDto getDto(String code, int count);
        List<SampleDto> getList(LocalDate date);
        Optional<SampleDto> findOptional(String code);
    }

    @Entity
    public static class SampleEntity {
        private Long id;
    }

    @NamedInterface("ApiReturningEntity")
    public interface ApiReturningEntity {
        SampleEntity getEntity(Long id);
    }

    @NamedInterface("ApiAcceptingEntity")
    public interface ApiAcceptingEntity {
        void saveEntity(SampleEntity entity);
    }

    @NamedInterface("ApiReturningListOfEntities")
    public interface ApiReturningListOfEntities {
        List<SampleEntity> getEntities();
    }

    @NamedInterface("ApiWithInputStream")
    public interface ApiWithInputStream {
        void readStream(InputStream stream);
    }

    @NamedInterface("ApiWithStream")
    public interface ApiWithStream {
        Stream<String> streamItems();
    }

    @Configuration(proxyBeanMethods = false)
    static class InvalidApiConfig {

        @Bean
        public ModuleLayoutInspector moduleLayoutInspector() {
            return new ModuleLayoutInspector();
        }

        @Bean
        public ModulithBridgeProperties modulithBridgeProperties() {
            ModulithBridgeProperties props = new ModulithBridgeProperties();
            props.setBasePackage("com.builtbyjuls.strangler.contract");
            return props;
        }

        @Bean
        public ModuleContractValidator moduleContractValidator(
                org.springframework.context.ApplicationContext applicationContext,
                ModuleLayoutInspector inspector,
                ModulithBridgeProperties properties
        ) {
            return new ModuleContractValidator(applicationContext, inspector, properties);
        }

        @Bean
        public ApiReturningEntity apiReturningEntity() {
            return id -> null;
        }
    }
}
