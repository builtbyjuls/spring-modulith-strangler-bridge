package com.builtbyjuls.strangler.proxy;

import java.lang.reflect.Proxy;
import java.util.Objects;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;

import com.builtbyjuls.strangler.config.ModulithBridgeProperties;
import com.builtbyjuls.strangler.parity.ShadowParityRegistry;
import com.builtbyjuls.strangler.parity.ShadowParityVerifier;
import com.builtbyjuls.strangler.transport.BridgeHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * FactoryBean that creates a remote-only dynamic proxy for a module API (@NamedInterface)
 * when the local implementing service bean has been pruned on the main node.
 *
 * @param <T> module API interface type
 */
public class RemoteBridgeProxyFactoryBean<T> implements FactoryBean<T>, BeanFactoryAware {

    private Class<T> interfaceType;
    private String moduleName;
    private BeanFactory beanFactory;
    private T proxyInstance;

    public RemoteBridgeProxyFactoryBean() {
    }

    public RemoteBridgeProxyFactoryBean(Class<T> interfaceType, String moduleName) {
        this.interfaceType = interfaceType;
        this.moduleName = moduleName;
    }

    public void setInterfaceType(Class<T> interfaceType) {
        this.interfaceType = interfaceType;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getObject() {
        if (proxyInstance == null) {
            Objects.requireNonNull(interfaceType, "interfaceType must not be null");
            Objects.requireNonNull(moduleName, "moduleName must not be null");

            BridgeHttpClient httpClient = beanFactory != null
                    ? beanFactory.getBeanProvider(BridgeHttpClient.class).getIfAvailable(BridgeHttpClient::new)
                    : new BridgeHttpClient();

            ModulithBridgeProperties properties = beanFactory != null
                    ? beanFactory.getBeanProvider(ModulithBridgeProperties.class).getIfAvailable(ModulithBridgeProperties::new)
                    : new ModulithBridgeProperties();

            ObjectMapper objectMapper = beanFactory != null
                    ? beanFactory.getBeanProvider(ObjectMapper.class).getIfAvailable()
                    : null;

            ShadowParityVerifier verifier = beanFactory != null
                    ? beanFactory.getBeanProvider(ShadowParityVerifier.class).getIfAvailable()
                    : null;

            ShadowParityRegistry registry = beanFactory != null
                    ? beanFactory.getBeanProvider(ShadowParityRegistry.class).getIfAvailable()
                    : null;

            ModulithBridgeInvocationHandler handler = new ModulithBridgeInvocationHandler(
                    null, // local target is null because bean was pruned
                    moduleName,
                    interfaceType,
                    properties,
                    httpClient,
                    objectMapper,
                    verifier,
                    registry
            );

            ClassLoader classLoader = interfaceType.getClassLoader() != null
                    ? interfaceType.getClassLoader()
                    : Thread.currentThread().getContextClassLoader();

            proxyInstance = (T) Proxy.newProxyInstance(
                    classLoader,
                    new Class<?>[]{interfaceType},
                    handler
            );
        }
        return proxyInstance;
    }

    @Override
    public Class<?> getObjectType() {
        return interfaceType;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
