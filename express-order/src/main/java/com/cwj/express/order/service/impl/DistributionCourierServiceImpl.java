package com.cwj.express.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cwj.express.common.config.redis.RedisConfig;
import com.cwj.express.common.enums.OrderStatusEnum;
import com.cwj.express.common.enums.SysRoleEnum;
import com.cwj.express.common.exception.ExceptionCast;
import com.cwj.express.common.model.response.CommonCode;
import com.cwj.express.domain.order.OrderEvaluate;
import com.cwj.express.domain.order.OrderInfo;
import com.cwj.express.domain.ucenter.SysUser;
import com.cwj.express.order.dao.OrderEvaluateMapper;
import com.cwj.express.order.dao.OrderInfoMapper;
import com.cwj.express.order.feignclient.ucenter.UcenterFeignClient;
import com.cwj.express.order.service.DistributionCourierService;
import com.cwj.express.order.service.OrderEvaluateService;
import com.cwj.express.order.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

@Slf4j
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Service
public class DistributionCourierServiceImpl implements DistributionCourierService {

    private final OrderInfoMapper orderInfoMapper;
    private final RedisService redisService;
    private final DefaultRedisScript<String> redisScript;
    private final StringRedisTemplate stringRedisTemplate;
    private final UcenterFeignClient ucenterFeignClient;

    /**
     * 实现思路请参考编写计划.txt，这里就不过多写注释了，相信我，纯理论看起来会更舒服
     * @param orderId 订单id
     * @param type 执行类型 first-支付之后分配 re-手动重新分配
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void distributionCourier(String orderId, String type) {
        // 查询对应订单id且配送员id为""的订单记录
        OrderInfo orderInfo = orderInfoMapper.selectOne(
                new QueryWrapper<OrderInfo>().
                        eq("id", orderId).
                        eq("courier_id", ""));
        orderInfo.setUpdateDate(null);
        // 一般情况下，如果是因网络波动重复发送的消息，就会直接跳过下面的if了，因为重复发送一般是很久没有收到回答信息
        int updateCount = 0;
        if (!ObjectUtils.isEmpty(orderInfo)){
            // 查询 redis 日志
            String logKey = RedisConfig.ORDER_COURIER_DATA + "::" + orderId;
            String courierId = redisService.get(logKey);
            if (!StringUtils.isEmpty(courierId)){
                orderInfo.setCourierId(courierId);
                // 只有支付后分配才将状态改为等待揽收
                if ("first".equals(type)){
                    orderInfo.setOrderStatus(OrderStatusEnum.WAIT_PICK_UP);
                }
                updateCount = orderInfoMapper.update(orderInfo, new QueryWrapper<OrderInfo>()
                        .eq("id", orderId).eq("courier_id", ""));
            }else {
                // 查询下单者的学校id(远程调用)
                SysUser user = ucenterFeignClient.getById(orderInfo.getUserId());
                // redis 选取该学校分数最高的配送员，并减掉该配送员分数10，记录日志
                String key = RedisConfig.COURIER_WEIGHT_DATA + "::" + user.getSchoolId();
                List<String> keys = Arrays.asList(key, logKey);
                courierId = stringRedisTemplate.execute(redisScript, keys, String.valueOf(RedisConfig.DISTRIBUTION_LOG_TIME_OUT));
                if (StringUtils.isEmpty(courierId)){
                    ExceptionCast.cast(CommonCode.COURIER_NOT_EXIST);
                }
                orderInfo.setCourierId(courierId);
                if ("first".equals(type)){
                    orderInfo.setOrderStatus(OrderStatusEnum.WAIT_PICK_UP);
                }
                updateCount = orderInfoMapper.update(orderInfo, new QueryWrapper<OrderInfo>()
                        .eq("id", orderId).eq("courier_id", ""));
            }
        }
        if (updateCount == 0){
            log.warn("【分配配送员业务】可能由于网络波动，消息被重复消费，订单号:{}", orderId);
        }
    }
}
