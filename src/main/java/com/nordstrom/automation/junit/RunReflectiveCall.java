package com.nordstrom.automation.junit;

import static com.nordstrom.automation.junit.LifecycleHooks.getFieldValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.Callable;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runners.model.FrameworkMethod;

import com.nordstrom.common.base.UncheckedThrow;

import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;

/**
 * This class declares the interceptor for the {@link org.junit.internal.runners.model.ReflectiveCallable#runReflectiveCall
 * runReflectiveCall} method.
 */
@SuppressWarnings("squid:S1118")
public class RunReflectiveCall {
    
    private static final ServiceLoader<MethodWatcher> methodWatcherLoader;
    private static final ThreadLocal<Map<Integer, DepthGauge>> methodDepth = ThreadLocal.withInitial(HashMap::new);
    
    static {
        methodWatcherLoader = ServiceLoader.load(MethodWatcher.class);
    }
    
    /**
     * Interceptor for the {@link org.junit.internal.runners.model.ReflectiveCallable#runReflectiveCall
     * runReflectiveCall} method.
     * 
     * @param callable {@code ReflectiveCallable} object being intercepted 
     * @param proxy callable proxy for the intercepted method
     * @return {@code anything} - value returned by the intercepted method
     * @throws Exception {@code anything} (exception thrown by the intercepted method)
     */
    @RuntimeType
    public static Object intercept(@This final Object callable, @SuperCall final Callable<?> proxy)
                    throws Exception {
        
        Object runner = null;
        Object target = null;
        FrameworkMethod method = null;
        Object[] params = null;

        try {
            Object owner = getFieldValue(callable, "this$0");
            if (owner instanceof FrameworkMethod) {
                method = (FrameworkMethod) owner;
                target = getFieldValue(callable, "val$target");
                params = getFieldValue(callable, "val$params");
                
                if (isParticleMethod(method)) {
                    if (target != null) {
                        runner = CreateTest.getRunnerForTarget(target);
                    } else {
                        runner = Run.getThreadRunner();
                    }
                }
            } else {
                runner = owner;
            }
        } catch (IllegalAccessException | NoSuchFieldException | SecurityException | IllegalArgumentException e) {
            // handled below
        }
        
        if (method == null) {
            return LifecycleHooks.callProxy(proxy);
        }
        
        Object result = null;
        Throwable thrown = null;

        try {
            fireBeforeInvocation(runner, target, method, params);
            result = LifecycleHooks.callProxy(proxy);
        } catch (Throwable t) {
            thrown = t;
        } finally {
            fireAfterInvocation(runner, target, method, thrown);
        }

        if (thrown != null) {
            throw UncheckedThrow.throwUnchecked(thrown);
        }

        return result;
    }
    
    /**
     * Get reference to an instance of the specified watcher type.
     * 
     * @param watcherType watcher type
     * @return optional watcher instance
     */
    public static Optional<MethodWatcher> getAttachedWatcher(
                    Class<? extends MethodWatcher> watcherType) {
        Objects.requireNonNull(watcherType, "[watcherType] must be non-null");
        synchronized(methodWatcherLoader) {
            for (MethodWatcher watcher : methodWatcherLoader) {
                if (watcher.getClass() == watcherType) {
                    return Optional.of(watcher);
                }
            }
        }
        return Optional.empty();
    }
    
    /**
     * Determine if the specified method is a test or configuration method.
     * 
     * @param method method whose type is in question
     * @return {@code true} if specified method is a particle; otherwise {@code false}
     */
    public static boolean isParticleMethod(FrameworkMethod method) {
        return ((null != method.getAnnotation(Test.class)) ||
                (null != method.getAnnotation(Before.class)) ||
                (null != method.getAnnotation(After.class)) ||
                (null != method.getAnnotation(BeforeClass.class)) ||
                (null != method.getAnnotation(AfterClass.class)));
    }
    
    /**
     * Fire the {@link MethodWatcher#beforeInvocation(Object, Object, FrameworkMethod, Object...) event.
     * <p>
     * If the {@code beforeInvocation} event for the specified method has already been fired, do nothing.
     * 
     * @param runner JUnit test runner
     * @param target "enhanced" object upon which the method was invoked
     * @param method {@link FrameworkMethod} object for the invoked method
     * @param params method invocation parameters
     * @return {@code true} if event the {@code beforeInvocation} was fired; otherwise {@code false}
     */
    private static boolean fireBeforeInvocation(Object runner, Object target, FrameworkMethod method, Object... params) {
        if ((runner != null) && (method != null)) {
            DepthGauge depthGauge = methodDepth.get().computeIfAbsent(methodHash(runner, method), k -> new DepthGauge());
            if (0 == depthGauge.increaseDepth()) {
                synchronized(methodWatcherLoader) {
                    for (MethodWatcher watcher : methodWatcherLoader) {
                        watcher.beforeInvocation(runner, target, method, params);
                    }
                }
                return true;
            }
        }
        return false;
    }
    
    /**
     * Fire the {@link MethodWatcher#afterInvocation(Object, Object, FrameworkMethod, Throwable) event.
     * <p>
     * If the {@code afterInvocation} event for the specified method has already been fired, do nothing.
     * 
     * @param runner JUnit test runner
     * @param target "enhanced" object upon which the method was invoked
     * @param method {@link FrameworkMethod} object for the invoked method
     * @param thrown exception thrown by method; null on normal completion
     * @return {@code true} if event the {@code afterInvocation} was fired; otherwise {@code false}
     */
    private static boolean fireAfterInvocation(Object runner, Object target, FrameworkMethod method, Throwable thrown) {
        if ((runner != null) && (method != null)) {
            DepthGauge depthGauge = methodDepth.get().computeIfAbsent(methodHash(runner, method), k -> new DepthGauge());
            if (0 == depthGauge.decreaseDepth()) {
                synchronized(methodWatcherLoader) {
                    for (MethodWatcher watcher : methodWatcherLoader) {
                        watcher.afterInvocation(runner, target, method, thrown);
                    }
                }
                return true;
            }
        }
        return false;
    }
    
    /**
     * Generate a hash code for the specified runner/method pair.
     * 
     * @param runner JUnit test runner
     * @param method {@link FrameworkMethod} object
     * @return hash code for the specified runner/method pair
     */
    public static int methodHash(Object runner, FrameworkMethod method) {
        return ((Thread.currentThread().hashCode() * 31) + runner.hashCode()) * 31 + method.hashCode();
    }
}
