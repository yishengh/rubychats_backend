package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bx.imclient.IMClient;
import com.bx.imcommon.enums.IMTerminalType;
import com.bx.imcommon.util.JwtUtil;
import com.bx.implatform.config.JwtProperties;
import com.bx.implatform.dto.LoginDTO;
import com.bx.implatform.dto.ModifyPwdDTO;
import com.bx.implatform.dto.RegisterDTO;
import com.bx.implatform.entity.Friend;
import com.bx.implatform.entity.GroupMember;
import com.bx.implatform.entity.User;
import com.bx.implatform.enums.ResultCode;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.UserMapper;
import com.bx.implatform.service.IFriendService;
import com.bx.implatform.service.IGroupMemberService;
import com.bx.implatform.service.IUserService;
import com.bx.implatform.session.SessionContext;
import com.bx.implatform.session.UserSession;
import com.bx.implatform.util.BeanUtils;
import com.bx.implatform.vo.LoginVO;
import com.bx.implatform.vo.OnlineTerminalVO;
import com.bx.implatform.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    RedisTemplate<String,Object> redisTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private IGroupMemberService groupMemberService;

    @Autowired
    private IFriendService friendService;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private IMClient imClient;
    /**
     * 用户登录
     *
     * @param dto 登录dto
     * @return 登录token
     */

    @Override
    public LoginVO login(LoginDTO dto) {
        User user = this.findUserByUserName(dto.getUserName());
        if(null == user){
            throw  new GlobalException(ResultCode.PROGRAM_ERROR,"用户不存在");
        }
        if(!passwordEncoder.matches(dto.getPassword(),user.getPassword())){
            throw  new GlobalException(ResultCode.PASSWOR_ERROR);
        }
        // 生成token
        UserSession session = BeanUtils.copyProperties(user,UserSession.class);
        session.setUserId(user.getId());
        session.setTerminal(dto.getTerminal());
        String strJson = JSON.toJSONString(session);
        String accessToken = JwtUtil.sign(user.getId(),strJson,jwtProperties.getAccessTokenExpireIn(),jwtProperties.getAccessTokenSecret());
        String refreshToken = JwtUtil.sign(user.getId(),strJson,jwtProperties.getRefreshTokenExpireIn(),jwtProperties.getRefreshTokenSecret());
        LoginVO vo = new LoginVO();
        vo.setAccessToken(accessToken);
        vo.setAccessTokenExpiresIn(jwtProperties.getAccessTokenExpireIn());
        vo.setRefreshToken(refreshToken);
        vo.setRefreshTokenExpiresIn(jwtProperties.getRefreshTokenExpireIn());
        return vo;
    }




    /**
     * 用refreshToken换取新 token
     *
     * @param refreshToken  刷新token
     * @return 登录token
     */
    @Override
    public LoginVO refreshToken(String refreshToken) {
        //验证 token
        if(!JwtUtil.checkSign(refreshToken, jwtProperties.getRefreshTokenSecret())){
            throw new GlobalException("refreshToken无效或已过期");
        }
        String strJson = JwtUtil.getInfo(refreshToken);
        Long userId = JwtUtil.getUserId(refreshToken);
        String accessToken = JwtUtil.sign(userId,strJson,jwtProperties.getAccessTokenExpireIn(),jwtProperties.getAccessTokenSecret());
        String newRefreshToken = JwtUtil.sign(userId,strJson,jwtProperties.getRefreshTokenExpireIn(),jwtProperties.getRefreshTokenSecret());
        LoginVO vo =new LoginVO();
        vo.setAccessToken(accessToken);
        vo.setAccessTokenExpiresIn(jwtProperties.getAccessTokenExpireIn());
        vo.setRefreshToken(newRefreshToken);
        vo.setRefreshTokenExpiresIn(jwtProperties.getRefreshTokenExpireIn());
        return vo;
    }

    /**
     * 用户注册
     *
     * @param dto 注册dto
     */
    @Override
    public void register(RegisterDTO dto) {
        User user = this.findUserByUserName(dto.getUserName());
        if(null != user){
            throw  new GlobalException(ResultCode.USERNAME_ALREADY_REGISTER);
        }
        user = BeanUtils.copyProperties(dto,User.class);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        this.save(user);
        log.info("注册用户，用户id:{},用户名:{},昵称:{}",user.getId(),dto.getUserName(),dto.getNickName());
    }


    @Override
    public void modifyPassword(ModifyPwdDTO dto) {
        UserSession session = SessionContext.getSession();
        User user = this.getById(session.getUserId());
        if(!passwordEncoder.matches(dto.getOldPassword(),user.getPassword())){
            throw  new GlobalException("旧密码不正确");
        }
        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        this.updateById(user);
        log.info("用户修改密码，用户id:{},用户名:{},昵称:{}",user.getId(),user.getUserName(),user.getNickName());
    }

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户信息
     */
    @Override
    public User findUserByUserName(String username) {
        LambdaQueryWrapper<User> queryWrapper =  Wrappers.lambdaQuery();
        queryWrapper.eq(User::getUserName,username);
        return this.getOne(queryWrapper);
    }


    /**
     * 更新用户信息，好友昵称和群聊昵称等冗余信息也会更新
     *
     * @param vo 用户信息vo
     */
    @Transactional
    @Override
    public void update(UserVO vo) {
        UserSession session = SessionContext.getSession();
        if(!session.getUserId().equals(vo.getId()) ){
            throw  new GlobalException(ResultCode.PROGRAM_ERROR,"不允许修改其他用户的信息!");
        }
        User user = this.getById(vo.getId());
        if(null == user){
            throw  new GlobalException(ResultCode.PROGRAM_ERROR,"用户不存在");
        }
        // 更新好友昵称和头像
        if(!user.getNickName().equals(vo.getNickName()) || !user.getHeadImageThumb().equals(vo.getHeadImageThumb())){
            QueryWrapper<Friend> queryWrapper = new QueryWrapper<>();
            queryWrapper.lambda().eq(Friend::getFriendId,session.getUserId());
            List<Friend> friends = friendService.list(queryWrapper);
            for(Friend friend: friends){
                friend.setFriendNickName(vo.getNickName());
                friend.setFriendHeadImage(vo.getHeadImageThumb());
            }
            friendService.updateBatchById(friends);
        }
        // 更新群聊中的头像
        if(!user.getHeadImageThumb().equals(vo.getHeadImageThumb())){
            List<GroupMember> members = groupMemberService.findByUserId(session.getUserId());
            for(GroupMember member:members){
                member.setHeadImage(vo.getHeadImageThumb());
            }
            groupMemberService.updateBatchById(members);
        }
        // 更新用户信息
        user.setNickName(vo.getNickName());
        user.setSex(vo.getSex());
        user.setSignature(vo.getSignature());
        user.setHeadImage(vo.getHeadImage());
        user.setHeadImageThumb(vo.getHeadImageThumb());
        this.updateById(user);
        log.info("用户信息更新，用户:{}}", user);
    }

    /**
     * 根据用户昵id查询用户以及在线状态
     *
     * @param id 用户id
     * @return 用户信息
     */
    @Override
    public UserVO findUserById(Long id) {
        User user = this.getById(id);
        UserVO vo = BeanUtils.copyProperties(user,UserVO.class);
        vo.setOnline(imClient.isOnline(id));
        return vo;
    }

    /**
     * 根据用户昵称查询用户，最多返回20条数据
     *
     * @param name 用户名或昵称
     * @return 用户列表
     */
    @Override
    public List<UserVO> findUserByName(String name) {
        LambdaQueryWrapper<User> queryWrapper = Wrappers.lambdaQuery();
        queryWrapper.like(User::getUserName,name)
            .or()
            .like(User::getNickName,name)
            .last("limit 20");
        List<User> users = this.list(queryWrapper);
        List<Long> userIds = users.stream().map(User::getId).collect(Collectors.toList());
        List<Long> onlineUserIds = imClient.getOnlineUser(userIds);
        return users.stream().map(u-> {
            UserVO vo = BeanUtils.copyProperties(u,UserVO.class);
            vo.setOnline(onlineUserIds.contains(u.getId()));
            return vo;
        }).collect(Collectors.toList());
    }



    /**
     * 获取用户在线的终端类型
     *
     * @param userIds 用户id，多个用‘,’分割
     * @return 在线用户终端
     */
    @Override
    public List<OnlineTerminalVO> getOnlineTerminals(String userIds) {
        List<Long> userIdList = Arrays.stream(userIds.split(","))
            .map(Long::parseLong).collect(Collectors.toList());
        // 查询在线的终端
        Map<Long,List<IMTerminalType>> terminalMap = imClient.getOnlineTerminal(userIdList);
        // 组装vo
        List<OnlineTerminalVO> vos = new LinkedList<>();
        terminalMap.forEach((userId,types)->{
            List<Integer> terminals = types.stream().map(IMTerminalType::code).collect(Collectors.toList());
            vos.add(new OnlineTerminalVO(userId,terminals));
        });
        return vos;
    }
}
