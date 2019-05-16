# 分布式锁学习笔记

## 1	分布式锁实现方式
 ![](http://www.ycnote.com/content/plugins/kl_album/upload/201905/ba440690dc76f30c946599328bb03fcc201905161052231053355894.png)  
### 1.1	基于数据库实现
#### 1.1.1	数据库表
优点：实现简单易于理解
	   缺点：阻塞依靠自旋实现，客户端崩溃会造成锁泄露无法释放。不建议使用。
#### 1.1.2	数据库排他锁
优点：实现简单易于理解
缺点：阻塞依靠自旋实现，增加数据库压力。

#### 1.2	基于缓存实现
优点：性能高
缺点：阻塞依靠自旋实现，客户端崩溃无法及时释放锁需要等待锁超时，客户端执行任务超过锁超时时间，锁会实效。

#### 1.3	基于zookeeper实现
优点：临时节点避免了锁泄露，watch机制避免阻塞自旋，顺序节点避免惊群。
缺点：需要了解zookeeper。

## 2	Curator基于zookeeper分布式锁实现方式
Zookeeper实现分布式锁有多种方式，网上有一实现方式有羊群效应（又称惊群效应），这总实现方式比较简单这里就不说了。我们谈一下Curator实现的分布式锁，优化了惊群效应，也就是常说的优化锁。

### InterProcessMutex
一个可以跨jvm工作的可重入互斥体。使用zookepeer来锁。所有jvm中的所有进程
使用相同的锁程将实现进程间的临界段。而且，这个互斥量是“公平”-每个用户将按要求的顺序获得互斥量(从ZK的角度来看)
	
### 	基本原理：
   一个锁对应一个基本节点，每一个线程来获取锁，会在基本节点下，创建一个有序的临时节点。while判断自己的序临时节点序为是自小的，如果是就获取到了这把锁进行return，如果不是watch监听自己前一个节点。当前线程进入wait状态。
	
  watch如果监听到前一个节点被删除，会notify 通知wait状态的线程。线程结束wait状态。进入while循环获取到锁，或者变更监听的节点。

  其余特性比如保证可重入，超时自动释放，等未作深入研究，欢迎大家补充。
	
以下是Curator部分源码对基本原理的验证：

###    备注
  当前 zookeeper 的稳定版本是3.4，Curator官方推荐的zookeeper版本是3.5说3.5已经在很多生产环境使用了。Curator最新版本4.2支持zookeeper3.5，同时支持zookeeper3.4。如果使用的的zookeeper3.4需要在Curator中忽略zookeeper客户端3.5版本强制指定3.4

String attemptLock(long time, TimeUnit unit, byte[] lockNodeBytes) throws Exception
{
    final long      startMillis = System.currentTimeMillis();
    final Long      millisToWait = (unit != null) ? unit.toMillis(time) : null;
    final byte[]    localLockNodeBytes = (revocable.get() != null) ? new byte[0] : lockNodeBytes;
    int             retryCount = 0;

    String          ourPath = null;
    boolean         hasTheLock = false;
    boolean         isDone = false;
    while ( !isDone )
    {
        isDone = true;

        try
        {
            ourPath = driver.createsTheLock(client, path, localLockNodeBytes);
            hasTheLock = internalLockLoop(startMillis, millisToWait, ourPath);
        }
        catch ( KeeperException.NoNodeException e )
        {
            // gets thrown by StandardLockInternalsDriver when it can't find the lock node
            // this can happen when the session expires, etc. So, if the retry allows, just try it all again
            if ( client.getZookeeperClient().getRetryPolicy().allowRetry(retryCount++, System.currentTimeMillis() - startMillis, RetryLoop.getDefaultRetrySleeper()) )
            {
                isDone = false;
            }
            else
            {
                throw e;
            }
        }
    }

    if ( hasTheLock )
    {
        return ourPath;
    }

    return null;
}


private boolean internalLockLoop(long startMillis, Long millisToWait, String ourPath) throws Exception
{
    boolean     haveTheLock = false;
    boolean     doDelete = false;
    try
    {
        if ( revocable.get() != null )
        {
            client.getData().usingWatcher(revocableWatcher).forPath(ourPath);
        }

        while ( (client.getState() == CuratorFrameworkState.STARTED) && !haveTheLock )
        {
            List<String>        children = getSortedChildren();
            String              sequenceNodeName = ourPath.substring(basePath.length() + 1); // +1 to include the slash

            PredicateResults    predicateResults = driver.getsTheLock(client, children, sequenceNodeName, maxLeases);
            if ( predicateResults.getsTheLock() )
            {
                haveTheLock = true;
            }
            else
            {
                String  previousSequencePath = basePath + "/" + predicateResults.getPathToWatch();

                synchronized(this)
                {
                    try
                    {
                        // use getData() instead of exists() to avoid leaving unneeded watchers which is a type of resource leak
                        client.getData().usingWatcher(watcher).forPath(previousSequencePath);
                        if ( millisToWait != null )
                        {
                            millisToWait -= (System.currentTimeMillis() - startMillis);
                            startMillis = System.currentTimeMillis();
                            if ( millisToWait <= 0 )
                            {
                                doDelete = true;    // timed out - delete our node
                                break;
                            }

                            wait(millisToWait);
                        }
                        else
                        {
                            wait();
                        }
                    }
                    catch ( KeeperException.NoNodeException e )
                    {
                        // it has been deleted (i.e. lock released). Try to acquire again
                    }
                }
            }
        }
    }
    catch ( Exception e )
    {
        ThreadUtils.checkInterrupted(e);
        doDelete = true;
        throw e;
    }
    finally
    {
        if ( doDelete )
        {
            deleteOurPath(ourPath);
        }
    }
    return haveTheLock;
}

private final Watcher watcher = new Watcher()
{
    @Override
    public void process(WatchedEvent event)
    {
        client.postSafeNotify(LockInternals.this);
    }
};



default CompletableFuture<Void> postSafeNotify(Object monitorHolder)
{
    return runSafe(() -> {
        synchronized(monitorHolder) {
            monitorHolder.notifyAll();
        }
    });
}
  
##### 测试demo 见 LockApplicationTests 
##### zookeeper 连接配置：ZKConfig application.properties
