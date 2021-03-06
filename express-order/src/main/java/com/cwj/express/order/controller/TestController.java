package com.cwj.express.order.controller;

import com.cwj.express.api.order.OrderControllerApi;
import com.cwj.express.common.config.auth.AuthorizeConfig;
import com.cwj.express.common.config.redis.RedisConfig;
import com.cwj.express.common.enums.PaymentStatusEnum;
import com.cwj.express.common.enums.PaymentTypeEnum;
import com.cwj.express.common.model.response.ResponseResult;
import com.cwj.express.common.web.BaseController;
import com.cwj.express.domain.order.OrderInfo;
import com.cwj.express.domain.order.OrderPayment;
import com.cwj.express.domain.ucenter.SysUser;
import com.cwj.express.order.dao.OrderInfoMapper;
import com.cwj.express.order.dao.OrderPaymentMapper;
import com.cwj.express.order.feignclient.ucenter.UcenterFeignClient;
import com.cwj.express.order.service.OrderEvaluateService;
import com.cwj.express.order.service.OrderInfoService;
import com.cwj.express.order.service.OrderPaymentService;
import com.cwj.express.order.service.RedisService;
import com.cwj.express.utils.ExpressOauth2Util;
import com.cwj.express.vo.order.OrderDashboardVO;
import com.cwj.express.vo.order.OrderInfoVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 替代单元测试方便突发测试
 */

@RequestMapping("/orderTest")
@RestController
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class TestController extends BaseController {
    private final DefaultRedisScript<String> redisScript;
    private final StringRedisTemplate stringRedisTemplate;
    private final UcenterFeignClient ucenterFeignClient;
    private final OrderInfoService orderInfoService;
    private final RedisService redisService;
    private final OrderInfoMapper orderInfoMapper;
    private final OrderPaymentMapper orderPaymentMapper;


    /**
     * 测试lua脚本
     * 记得先初始化一些数据
     */
    @GetMapping("/luaTest")
    public String test(){
        List<String> keys = Arrays.asList("qid1", "orderid");
        String res = stringRedisTemplate.execute(redisScript, keys, "600");
        return res;
    }

    @GetMapping("/test")
    public String test2(){
        String key = RedisConfig.COURIER_WEIGHT_DATA + "::3237";
        Long count = redisService.zcard(key);
        return String.valueOf(count);
    }

    /**
     * 初始化所有配送员分数为10000，会覆盖原有数据
     * todo 后期会换到启动初始化任务中
     */
    @GetMapping("/initScore")
    public String initScore(){
        // 查询所有配送员信息(所有，包括请假)，之后还会再加一张请假表，远程调用
        List<SysUser> allCouriers = ucenterFeignClient.getAllCouriers();
        if (ObjectUtils.isEmpty(allCouriers)){
            return "not couriers";
        }
        for (SysUser courier : allCouriers) {
            Double socre = orderInfoService.countCourierScore(courier.getId());
            String key = RedisConfig.COURIER_WEIGHT_DATA + "::" + courier.getSchoolId();
            redisService.zadd(key, courier.getId(), socre);
        }
        return "init has been successfully!";
    }

    @GetMapping("/getUserById/{userId}")
    public SysUser getUserById(@PathVariable String userId){
        return ucenterFeignClient.getById(userId);
    }

    @GetMapping("/initPayMent")
    public String initPayMent(){
        List<OrderInfo> orderInfos = orderInfoMapper.selectList(null);
        for (OrderInfo orderInfo : orderInfos) {
            OrderPayment build = OrderPayment.builder()
                    .orderId(orderInfo.getId())
                    .paymentStatus(PaymentStatusEnum.TRADE_SUCCESS)
                    .paymentType(PaymentTypeEnum.AliPay)
                    .payment(new BigDecimal(43))
                    .seller("2088102179254465")
                    .remark("")
                    .createDate(LocalDateTime.now()).build();
            orderPaymentMapper.insert(build);
        }

        return "success";
    }
}
