package io.choerodon.oauth.api.service.impl;

import io.choerodon.core.exception.CommonException;
import io.choerodon.core.notify.NoticeSendDTO;
import io.choerodon.oauth.api.dto.PasswordForgetDTO;
import io.choerodon.oauth.api.dto.UserDTO;
import io.choerodon.oauth.api.service.PasswordForgetService;
import io.choerodon.oauth.api.service.UserService;
import io.choerodon.oauth.api.validator.UserPasswordValidator;
import io.choerodon.oauth.api.validator.UserValidator;
import io.choerodon.oauth.core.password.PasswordPolicyManager;
import io.choerodon.oauth.core.password.domain.BasePasswordPolicyDTO;
import io.choerodon.oauth.core.password.domain.BaseUserDTO;
import io.choerodon.oauth.core.password.mapper.BasePasswordPolicyMapper;
import io.choerodon.oauth.core.password.record.PasswordRecord;
import io.choerodon.oauth.domain.entity.UserE;
import io.choerodon.oauth.infra.common.util.RedisTokenUtil;
import io.choerodon.oauth.infra.enums.PasswordFindException;
import io.choerodon.oauth.infra.feign.NotifyFeignClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * @author wuguokai
 */
@Service
public class PasswordForgetServiceImpl implements PasswordForgetService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PasswordForgetServiceImpl.class);
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    public static final String FORGET = "forgetPassword";   // 忘记密码消息code
    public static final String MODIFY = "modifyPassword";   // 修改密码消息code
    public static final String RESET_PAGE = "/oauth/password/reset_page";
    private UserService userService;
    private BasePasswordPolicyMapper basePasswordPolicyMapper;
    private PasswordPolicyManager passwordPolicyManager;
    private PasswordRecord passwordRecord;
    @Value("${choerodon.gateway.url}")
    private String gatewayUrl;
    @Value("${choerodon.reset-password.resetUrlExpireMinutes: 10}")
    private Long resetUrlExpireMinutes;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private NotifyFeignClient notifyFeignClient;
    @Autowired
    private RedisTokenUtil redisTokenUtil;
    @Autowired
    private UserValidator userValidator;
    @Autowired
    private MessageSource messageSource;
    private final UserPasswordValidator userPasswordValidator;

    public PasswordForgetServiceImpl(
            UserService userService,
            BasePasswordPolicyMapper basePasswordPolicyMapper,
            PasswordPolicyManager passwordPolicyManager,
            UserPasswordValidator userPasswordValidator,
            PasswordRecord passwordRecord) {
        this.userService = userService;
        this.basePasswordPolicyMapper = basePasswordPolicyMapper;
        this.passwordPolicyManager = passwordPolicyManager;
        this.passwordRecord = passwordRecord;
        this.userPasswordValidator = userPasswordValidator;
    }

    public void setNotifyFeignClient(NotifyFeignClient notifyFeignClient) {
        this.notifyFeignClient = notifyFeignClient;
    }

    public void setRedisTokenUtil(RedisTokenUtil redisTokenUtil) {
        this.redisTokenUtil = redisTokenUtil;
    }

    public void setUserValidator(UserValidator userValidator) {
        this.userValidator = userValidator;
    }

    public void setMessageSource(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public PasswordForgetDTO checkUserByEmail(String email) {
        PasswordForgetDTO passwordForgetDTO = new PasswordForgetDTO(false);
        if (!userValidator.emailValidator(email)) {
            passwordForgetDTO.setMsg(messageSource.getMessage(PasswordFindException.EMAIL_FORMAT_ILLEGAL.value(), null, Locale.ROOT));
            passwordForgetDTO.setCode(PasswordFindException.EMAIL_FORMAT_ILLEGAL.value());
            return passwordForgetDTO;
        }

        UserE user = userService.queryByEmail(email);
        if (null == user) {
            passwordForgetDTO.setMsg(messageSource.getMessage(PasswordFindException.ACCOUNT_NOT_EXIST.value(), null, Locale.ROOT));
            passwordForgetDTO.setCode(PasswordFindException.ACCOUNT_NOT_EXIST.value());
            return passwordForgetDTO;
        }

        if (Boolean.TRUE.equals(user.getLdap())) {
            passwordForgetDTO.setMsg(messageSource.getMessage(PasswordFindException.LDAP_CANNOT_CHANGE_PASSWORD.value(), null, Locale.ROOT));
            passwordForgetDTO.setCode(PasswordFindException.LDAP_CANNOT_CHANGE_PASSWORD.value());
            return passwordForgetDTO;
        }

        passwordForgetDTO.setSuccess(true);
        passwordForgetDTO.setUser(new UserDTO(user.getId(), user.getLoginName(), user.getEmail()));
        return passwordForgetDTO;
    }

    @Override
    public PasswordForgetDTO checkDisable(String email) {
        Long time = this.redisTokenUtil.getDisableTime(email);

        PasswordForgetDTO passwordForgetDTO = new PasswordForgetDTO();

        if (time != null) {
            passwordForgetDTO.setSuccess(false);
            passwordForgetDTO.setDisableTime(time);
            passwordForgetDTO.setMsg(messageSource.getMessage(PasswordFindException.DISABLE_SEND.value(), null, Locale.ROOT));
            passwordForgetDTO.setCode(PasswordFindException.DISABLE_SEND.value());
        }
        return passwordForgetDTO;
    }

    @Override
    public PasswordForgetDTO sendResetEmail(String email) {
        String keyType = RedisTokenUtil.SHORT_CODE;
        // 校验邮箱
        PasswordForgetDTO passwordForgetDTO = checkUserByEmail(email);
        if (Boolean.FALSE.equals(passwordForgetDTO.getSuccess())) {
            return passwordForgetDTO;
        }
        // 校验60秒内是否发送过邮件
        PasswordForgetDTO passwordForgetDTO1 = this.checkDisable(passwordForgetDTO.getUser().getEmail());
        if (Boolean.FALSE.equals(passwordForgetDTO1.getSuccess())) {
            return passwordForgetDTO1;
        }

        // 如果之前发送的token未失效，则让之前发送的token失效
        String emailKey = redisTokenUtil.createKey(keyType, email);
        String tokenKey = redisTokenUtil.getValueByKey(emailKey);
        if (tokenKey != null) {
            redisTokenUtil.expireByKey(tokenKey);
        }

        // 60秒内不能重复发送邮件，记录不能发送的时间
        redisTokenUtil.setDisableTime(passwordForgetDTO.getUser().getEmail());

        tokenKey = generateTokenKey(passwordForgetDTO.getUser().getEmail());
        String redirectUrl = gatewayUrl + RESET_PAGE + "/" + tokenKey;
        // 保存token和email，保存两条记录
        // emailKey -> tokenKey
        // tokenKey -> email
        redisTokenUtil.storeByKey(emailKey, tokenKey, resetUrlExpireMinutes, TimeUnit.MINUTES);
        redisTokenUtil.storeByKey(tokenKey, email, resetUrlExpireMinutes, TimeUnit.MINUTES);

        Map<String, Object> variables = new HashMap<>();
        variables.put("userName", passwordForgetDTO.getUser().getLoginName());
        variables.put("redirectUrl", redirectUrl);

        NoticeSendDTO noticeSendDTO = new NoticeSendDTO();
        NoticeSendDTO.User user = new NoticeSendDTO.User();
        user.setEmail(passwordForgetDTO.getUser().getEmail());
        List<NoticeSendDTO.User> users = new ArrayList<>();
        users.add(user);
        noticeSendDTO.setCode(FORGET);
        noticeSendDTO.setTargetUsers(users);
        noticeSendDTO.setParams(variables);
        try {
            notifyFeignClient.postNotice(noticeSendDTO);
            return passwordForgetDTO;
        } catch (CommonException e) {
            passwordForgetDTO.setSuccess(false);
            LOGGER.warn("The mail send error. {} {}", e.getCode(), e);
            return passwordForgetDTO;
        }
    }

    @Override
    public boolean checkTokenAvailable(String token) {
        String value = redisTokenUtil.getValueByKey(token);
        return value != null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PasswordForgetDTO resetPassword(String token, String password) {
        // 使用token，查出存在redis里的email,然后再根据email查出用户信息
        String email = getEmailByToken(token);
        UserE user = userService.queryByEmail(email);
        PasswordForgetDTO passwordForgetDTO = new PasswordForgetDTO();
        try {
            BaseUserDTO baseUser = new BaseUserDTO();
            BeanUtils.copyProperties(user, baseUser);
            baseUser.setPassword(password);
            BasePasswordPolicyDTO basePasswordPolicyDO = new BasePasswordPolicyDTO();
            basePasswordPolicyDO.setOrganizationId(user.getOrganizationId());
            basePasswordPolicyDO = basePasswordPolicyMapper.selectOne(basePasswordPolicyDO);
            passwordPolicyManager.passwordValidate(password, baseUser, basePasswordPolicyDO);
            userPasswordValidator.validate(password, user.getOrganizationId(), true);
        } catch (CommonException e) {
            LOGGER.error(e.getMessage());
            passwordForgetDTO.setSuccess(false);
            passwordForgetDTO.setMsg(e.getMessage());
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return passwordForgetDTO;
        }
        user.setPassword(ENCODER.encode(password));
        UserE userE = userService.updateSelective(user);
        if (userE != null) {
            passwordRecord.updatePassword(user.getId(), ENCODER.encode(password));
            passwordForgetDTO.setSuccess(true);
            redisTokenUtil.expireByKey(token);
            passwordForgetDTO.setUser(new UserDTO(userE.getId(), userE.getLoginName(), user.getEmail()));

            this.sendSiteMsg(user.getId(), user.getRealName());
            return passwordForgetDTO;
        }

        return new PasswordForgetDTO(false);
    }

    private String getEmailByToken(String token) {
        return redisTokenUtil.getValueByKey(token);
    }

    private String generateTokenKey(String email) {
        String token = UUID.randomUUID().toString() + passwordEncoder.encode(email);
        // 去掉特殊字符"/"
        token = token.replaceAll("\\/","");
        return token;
    }

    private void sendSiteMsg(Long userId, String userName) {
        Map<String, Object> paramsMap = new HashMap<>();
        paramsMap.put("userName", userName);
        NoticeSendDTO noticeSendDTO = new NoticeSendDTO();
        NoticeSendDTO.User user = new NoticeSendDTO.User();
        user.setId(userId);
        List<NoticeSendDTO.User> users = new ArrayList<>();
        users.add(user);
        noticeSendDTO.setCode(MODIFY);
        noticeSendDTO.setTargetUsers(users);
        try {
            notifyFeignClient.postNotice(noticeSendDTO);
        } catch (CommonException e) {
            LOGGER.warn("The site msg send error. {} {}", e.getCode(), e);
        }
    }
}