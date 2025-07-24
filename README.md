### 实战篇

#### 商品查询缓存

##### 添加缓存

**核心：**先查缓存，缓存没有再查数据库。查数据库后及时更新缓存。

##### 更新缓存

**核心：**先修改数据库，再删除缓存。

##### 缓存穿透

**原因：**查询的数据在缓存和数据库中都没有，请求就全部打到数据库。

| 解决方案   | 优点                    | 缺点                                       |
| ---------- | ----------------------- | ------------------------------------------ |
| 缓存空对象 | 实现简单，维护方便      | 额外内存消耗、可能造成短期的数据不一致问题 |
| 布隆过滤   | 内存占用少，没有多余key | 实现复杂、存在误判的可能                   |

##### 缓存击穿

**原因：**一个被高并发访问并且缓存重建业务较复杂的key突然失效了，无数的请求会在瞬间打到数据库。

| 解决方案 | 优点                                   | 缺点                                     |
| -------- | -------------------------------------- | ---------------------------------------- |
| 互斥锁   | 没有额外内存消耗、保证一致性、实现简单 | 线程需要等待、性能受到影响、会有死锁风险 |
| 逻辑过期 | 线程无需等待，性能较好                 | 不保证一致性、额外内存消耗、实现复杂     |

##### 缓存雪崩

**原因：**同一时段大量的缓存key同时失效或者redis服务宕机，导致大量请求打到数据库。

**解决方案：**给不同的key的TTL添加随机值、使用redis集群提高服务可用性。

#### 优惠券秒杀

##### 全局唯一ID

当用户抢购优惠券时，会生成订单并保存到tb_voucher_order这张表中，而订单表如果使用数据库自增ID就会存在一些问题：

**ID的规律性太明显。**

**受单表数据量的限制。**

我们可基于Redis实现**全局唯一ID生成器**，用于分布式系统下生成唯一ID。

```java
@Component
public class RedisIdWorker {

    /**
     * 起始的时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        // 1、生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2、生成序列号
        // 2.1、获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2、自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3、拼接并返回
        return timestamp << COUNT_BITS | count;
    }

}
```

##### 超卖问题

超卖问题分析：原有代码中是这样写的：

```java
if (voucher.getStock() < 1) {
    // 库存不足
    return Result.fail("库存不足！");
}
//5，扣减库存
boolean success = seckillVoucherService.update()
        .setSql("stock= stock -1")
        .eq("voucher_id", voucherId).update();
if (!success) {
    //扣减库存
    return Result.fail("库存不足！");
}
```

假设线程1过来查询库存，判断出来库存大于1，正准备去扣减库存，但是还没有来得及去扣减，此时线程2过来，线程2也去查询库存，发现这个数量一定也大于1，那么这两个线程都会去扣减库存，最终多个线程都会去扣减库存，此时就会出现库存的超卖问题。

![image-20250720213107857](assets/image-20250720213107857.png)

超卖问题是典型的多线程安全问题，针对这一问题的常见解决方案就是加锁：而对于加锁，我们通常有两种解决方案

![image-20250720213410286](assets/image-20250720213410286.png)

使用乐观锁解决超卖问题，乐观锁常用的两种实现：1-数据版本（Version）记录比较，2-时间戳记录比较。

**修改代码方案一：**

VoucherOrderServiceImpl在扣减库存时，改为：

```java
boolean success = seckillVoucherService.update()
            .setSql("stock= stock -1") //set stock = stock -1
            .eq("voucher_id", voucherId).eq("stock",voucher.getStock()).update(); //where id = ？ and stock = ?
```

以上逻辑的核心含义是：只要我扣减库存时的库存和之前我查询到的库存是一样的，就意味着没有人在中间修改过库存，那么此时就是安全的。但是以上这种方式通过测试发现会有很多失败的情况，失败的原因在于：在使用乐观锁过程中假设100个线程同时都拿到了100的库存，然后大家一起去进行扣减，但是100个人中只有1个人能扣减成功，其他的人在处理时，他们在扣减时，库存已经被修改过了，所以此时其他线程都会失效。

**修改代码方案二：**

之前的方式要修改前后都保持一致，但是这样我们分析过，成功的概率太低，所以我们的乐观锁需要变一下，改成stock大于0 即可。

```java
boolean success = seckillVoucherService.update()
            .setSql("stock= stock -1")
            .eq("voucher_id", voucherId).update().gt("stock",0); //where id = ? and stock > 0
```

##### 一人一单

**需求：**修改秒杀业务，要求同一个优惠券，一个用户只能下一单。

**现在的问题：**优惠券是为了引流，但目前，一个人可以无限制抢这个优惠券，所以我们应当加一层逻辑，让一个用户对同一个优惠券只能下一个单。

**改进后逻辑：**判断秒杀活动是否开始，如果开始，则再判断库存是否充足，然后再根据优惠券id和用户id查询是否已经下过这个订单，若下过这个订单，则不再下单，否则进行下单操作。

![image-20250721214227823](assets/image-20250721214227823.png)

**初步代码：增加一人一单逻辑**

```java
@Override
public Result seckillVoucher(Long voucherId) {
    // 1.查询优惠券
    SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
    // 2.判断秒杀是否开始
    if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
        // 尚未开始
        return Result.fail("秒杀尚未开始！");
    }
    // 3.判断秒杀是否已经结束
    if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
        // 尚未开始
        return Result.fail("秒杀已经结束！");
    }
    // 4.判断库存是否充足
    if (voucher.getStock() < 1) {
        // 库存不足
        return Result.fail("库存不足！");
    }
    // 5.一人一单逻辑
    // 5.1.用户id
    Long userId = UserHolder.getUser().getId();
    int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
    // 5.2.判断是否存在
    if (count > 0) {
        // 用户已经购买过了
        return Result.fail("用户已经购买过一次！");
    }

    //6，扣减库存
    boolean success = seckillVoucherService.update()
            .setSql("stock= stock -1")
            .eq("voucher_id", voucherId).update();
    if (!success) {
        //扣减库存
        return Result.fail("库存不足！");
    }
    //7.创建订单
    VoucherOrder voucherOrder = new VoucherOrder();
    // 7.1.订单id
    long orderId = redisIdWorker.nextId("order");
    voucherOrder.setId(orderId);

    voucherOrder.setUserId(userId);
    // 7.3.代金券id
    voucherOrder.setVoucherId(voucherId);
    save(voucherOrder);

    return Result.ok(orderId);
}
```

**存在问题：**并发过来，查询数据库，都不存在订单，也会创建多个订单。所以我们还是需要加锁，但是乐观锁比较适合更新数据，现在是插入数据，所以我们需要使用悲观锁操作。

**添加锁：**初始方案是封装了一个createVoucherOrder方法，同时为了确保线程安全，在方法上添加一把synchronized锁。

```java
@Transactional
public synchronized Result createVoucherOrder(Long voucherId) {
	Long userId = UserHolder.getUser().getId();
         // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            return Result.fail("库存不足！");
        }

        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2.用户id
        voucherOrder.setUserId(userId);
        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 7.返回订单id
        return Result.ok(orderId);
}
```

**问题：**这样添加锁，锁的粒度太粗，使用锁的过程中，控制锁粒度是非常重要的。如果锁的粒度太大，会导致每个线程进来都会锁住，锁 粒度太小，可能锁不住。

**改进：**intern()这个方法是从常量池中拿到数据，如果我们直接使用userId.toString()他拿到的对象实际上是不同的对象，是new出来的对象。使用锁必须保证锁是同一把，所以我们需要使用intern()方法。

```java
@Transactional
public  Result createVoucherOrder(Long voucherId) {
	Long userId = UserHolder.getUser().getId();
	synchronized(userId.toString().intern()){
         // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            return Result.fail("库存不足！");
        }

        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2.用户id
        voucherOrder.setUserId(userId);
        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 7.返回订单id
        return Result.ok(orderId);
    }
}
```

但是以上代码还是存在问题，问题的原因在于当前方法被spring的事务控制，如果你在方法内部加锁，可能会导致当前方法事务还没有提交，但是锁已经释放也会导致问题，所以我们选择将当前方法整体包裹起来，确保事务不会出现问题，如下：

在seckillVoucher 方法中，添加以下逻辑，这样就能保证事务的特性，同时也控制了锁的粒度。

<img src="assets/image-20250721221427579.png" alt="image-20250721221427579" style="zoom: 50%;" align="left">

但是以上做法依然有问题，因为你调用的方法，其实是this.的方式调用的，事务想要生效，还得利用代理来生效，所以这个地方，我们需要获得原始的事务对象，来操作事务。

![image-20250721222347831](assets/image-20250721222347831.png)

##### 问题：集群下的并发

通过加锁可以解决在单机情况下的一人一单安全问题，但是在集群模式下就不行了。

1、我们将服务启动两份，端口分别为8081和8082：

<img src="assets/image-20250724232311622.png" alt="image-20250724232311622" style="zoom:60%;" align="left">

2、然后修改nginx的conf目录下的nginx.conf文件，配置反向代理和负载均衡：

<img src="assets/image-20250724232512042.png" alt="image-20250724232512042" style="zoom:60%;" align="left">

**有关锁失效原因分析**

现在我们部署了多个tomcat，每个tomcat都有一个属于自己的jvm。那么假设在服务器A的tomcat内部，有两个线程，这两个线程由于使用的是同一份代码，那么他们的锁对象是同一个，是可以实现互斥的。但是现在服务器B的tomcat内部，又有两个线程，但是他们的锁对象写的虽然和服务器A一样，但是锁对象却不是同一个，所以线程3和线程4可以实现互斥，但是却无法和线程1和线程2实现互斥。这就是集群环境下，syn锁失效的原因，在这种情况下，我们就需要使用分布式锁来解决这个问题。

<img src="assets/image-20250724233059939.png" alt="image-20250724233059939" style="zoom: 50%;" align="left">

##### 方案：使用分布式锁

分布式锁：满足分布式系统或集群模式下多进程可见并且互斥的锁。

简易实现：基于Redis的setnx命令实现获取锁，del命令及expire命令实现锁释放。

一、初始版本分布式锁实现：

利用setnx方法进行加锁，同时增加过期时间，防止死锁，此方法可以保证加锁和增加过期时间具有原子性

```java
private static final String KEY_PREFIX="lock:"
@Override
public boolean tryLock(long timeoutSec) {
    // 获取线程标示
    String threadId = Thread.currentThread().getId()
    // 获取锁
    Boolean success = stringRedisTemplate.opsForValue()
            .setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
    return Boolean.TRUE.equals(success);
}
```

释放锁，防止删除别人的锁

```java
public void unlock() {
    //通过del删除锁
    stringRedisTemplate.delete(KEY_PREFIX + name);
}
```

修改业务代码

```java
@Override
public Result seckillVoucher(Long voucherId) {
    // 1.查询优惠券
    SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
    // 2.判断秒杀是否开始
    if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
        // 尚未开始
        return Result.fail("秒杀尚未开始！");
    }
    // 3.判断秒杀是否已经结束
    if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
        // 尚未开始
        return Result.fail("秒杀已经结束！");
    }
    // 4.判断库存是否充足
    if (voucher.getStock() < 1) {
        // 库存不足
        return Result.fail("库存不足！");
    }
    Long userId = UserHolder.getUser().getId();
    //创建锁对象(新增代码)
    SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
    //获取锁对象
    boolean isLock = lock.tryLock(1200);
    //加锁失败
    if (!isLock) {
        return Result.fail("不允许重复下单");
    }
    try {
        //获取代理对象(事务)
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        return proxy.createVoucherOrder(voucherId);
    } finally {
        //释放锁
        lock.unlock();
    }
}
```

问题：多线程时，其他线程删除了本线程持有的锁。

二、解决分布式锁误删问题：

核心逻辑：在存入锁时，放入自己线程的标识。在删除锁时，判断当前这把锁的标识是不是自己存入的，如果是，则进行删除，如果不是，则不进行删除。

加锁

```java
private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
@Override
public boolean tryLock(long timeoutSec) {
   // 获取线程标示
   String threadId = ID_PREFIX + Thread.currentThread().getId();
   // 获取锁
   Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
   return Boolean.TRUE.equals(success);
}
```

释放锁

```java
public void unlock() {
    // 获取线程标示
    String threadId = ID_PREFIX + Thread.currentThread().getId();
    // 获取锁中的标示
    String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
    // 判断标示是否一致
    if(threadId.equals(id)) {
        // 释放锁
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
```

问题：判断标识与锁释放操作是两步，非原子操作，高并发下可能会出现问题。

三、LUA脚本解决多条命令的原子性问题：

Redis提供的调用函数，语法如下：

```lua
redis.call('命令名称','key','其他参数',...)
```

例如，我们要执行set name jack，则脚本是这样：

```lua
-- 执行 set name jack
redis.call('set','name','jack')
```

例如，我们要先执行set name Rose，再执行get name，则脚本如下：

```lua
-- 先执行 set name jack
redis.call('set','name','Rose')
-- 再执行 get name
local name = redis.call('get','name')
-- 返回
return name
```

写好脚本以后，需要用Redis命令来调用脚本，调用脚本的常见命令如下：

<img src="assets/image-20250724235622265.png" alt="image-20250724235622265" style="zoom:50%;" align="left">

例如，我们要执行 redis.call('set', 'name', 'jack') 这个脚本，语法如下：

<img src="assets/image-20250724235705201.png" alt="image-20250724235705201" style="zoom: 67%;" align="left">

如果脚本中的key、value不想写死，可以作为参数传递。key类型参数会放入KEYS数组，其它参数会放入ARGV数组，在脚本中可以从KEYS和ARGV数组获取这些参数：

<img src="assets/image-20250724235831117.png" alt="image-20250724235831117" style="zoom: 67%;" align="left">

最终我们操作redis的拿锁、比锁、删锁的lua脚本就会变成这样：

```lua
-- 这里的 KEYS[1] 就是锁的key，这里的ARGV[1] 就是当前线程标示
-- 获取锁中的标示，判断是否与当前线程标示一致
if (redis.call('GET', KEYS[1]) == ARGV[1]) then
  -- 一致，则删除锁
  return redis.call('DEL', KEYS[1])
end
-- 不一致，则直接返回
return 0
```

四、分布式锁改造

lua脚本本身并不需要大家花费太多时间去研究，只需要知道如何调用，大致是什么意思即可，所以在笔记中并不会详细的去解释这些lua表达式的含义。

我们的RedisTemplate中，可以利用execute方法去执行lua脚本，参数对应关系就如下图

<img src="assets/image-20250725000715758.png" alt="image-20250725000715758" style="zoom: 50%;" align="left">

Java代码，锁释放逻辑

```java
private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

public void unlock() {
    // 调用lua脚本
    stringRedisTemplate.execute(
            UNLOCK_SCRIPT,
            Collections.singletonList(KEY_PREFIX + name),
            ID_PREFIX + Thread.currentThread().getId());
}
// 经过以上代码改造后，我们就能够实现 拿锁比锁删锁的原子性动作了~
```

