package com.ycnote.lock.conf;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author youchao
 * zookeeper配置
 */
@Configuration
public class ZKConfig {

    @Value("${zk.url}")
    String zkUrl;

    @Bean
    public CuratorFramework curatorFramework() {
        //最多重试3次，重试间隔1秒
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
        //配置链接地址 session超时时间，链接超时时间，重连策略
        CuratorFramework client = CuratorFrameworkFactory
                .newClient(zkUrl, 10000, 5000, retryPolicy);
        client.start();
        return client;
    }

}
