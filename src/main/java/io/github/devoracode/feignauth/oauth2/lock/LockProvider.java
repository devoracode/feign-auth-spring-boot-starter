package io.github.devoracode.feignauth.oauth2.lock;

import java.util.concurrent.Callable;

public interface LockProvider {

    <T> T execute(String key, Callable<T> callable);
}
