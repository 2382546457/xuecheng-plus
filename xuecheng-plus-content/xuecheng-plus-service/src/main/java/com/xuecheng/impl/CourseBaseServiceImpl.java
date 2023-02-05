package com.xuecheng.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xuecheng.base.model.PageParams;
import com.xuecheng.base.model.PageResult;
import com.xuecheng.content.model.dto.AddCourseDto;
import com.xuecheng.content.model.dto.CourseBaseInfoDto;
import com.xuecheng.content.model.dto.QueryCourseParamsDto;
import com.xuecheng.content.model.po.CourseBase;
import com.xuecheng.content.model.po.CourseCategory;
import com.xuecheng.content.model.po.CourseMarket;
import com.xuecheng.mapper.CourseBaseMapper;
import com.xuecheng.mapper.CourseCategoryMapper;
import com.xuecheng.mapper.CourseMarketMapper;
import com.xuecheng.servicce.CourseBaseService;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * @author : 小何
 * @Description :
 * @date : 2023-02-04 14:18
 */
@Service
@Transactional
public class CourseBaseServiceImpl extends ServiceImpl<CourseBaseMapper, CourseBase> implements CourseBaseService {


    @Autowired
    private CourseBaseMapper courseBaseMapper;

    @Autowired
    private CourseMarketMapper courseMarketMapper;

    @Autowired
    private CourseCategoryMapper courseCategoryMapper;


    /**
     * 查询所有课程
     * @param params 分页信息
     * @param dto 查询课程信息
     * @return 分页结果类
     */
    @Override
    public PageResult<CourseBase> queryCourseBaseList(PageParams params, QueryCourseParamsDto dto) {
        // 1. 根据条件查询课程
        LambdaQueryWrapper<CourseBase> lambda = new LambdaQueryWrapper<>();
        // 1.1 根据名称模糊查询
        lambda.like(StringUtils.isNotEmpty(dto.getCourseName()), CourseBase::getName, dto.getCourseName());
        // 1.2 根据审核状态查询
        lambda.eq(StringUtils.isNotEmpty(dto.getAuditStatus()), CourseBase::getAuditStatus, dto.getAuditStatus());
        // 1.3 根据课程发布状态
        lambda.eq(StringUtils.isNotEmpty(dto.getPublishStatus()), CourseBase::getStatus, dto.getPublishStatus());

        // 2. 分页条件
        Page<CourseBase> page = new Page<>(params.getPageNo(), params.getPageSize());


        // 查询
        Page<CourseBase> pageResult = courseBaseMapper.selectPage(page, lambda);

        // List<T> items, long counts, long page, long pageSize
        PageResult<CourseBase> result = new PageResult<CourseBase>(
                pageResult.getRecords(),
                pageResult.getTotal(),
                params.getPageNo(),
                params.getPageSize()
        );
        return result;
    }

    /**
     * 新增课程
     * @param companyId 机构id, 当前登录用户的id
     * @param addCourseDto 新增的课程的信息
     * @return 新增的课程的信息
     */
    @Override
    public CourseBaseInfoDto createCourse(Long companyId, AddCourseDto addCourseDto) throws RuntimeException {
        if ("201001".equals(addCourseDto.getCharge()) && new Float(0).equals(addCourseDto.getPrice())) {
            throw new RuntimeException("收费课程必须填写价格");
        }

        // 先将数据封装好插入 course_base 表中
        CourseBase courseBase = new CourseBase();
        BeanUtils.copyProperties(addCourseDto, courseBase);
        courseBase.setCompanyId(companyId);
        courseBase.setCreateDate(LocalDateTime.now());
        courseBase.setChangeDate(LocalDateTime.now());
        courseBase.setStatus("203001"); // 发布状态默认为未发布
        courseBase.setAuditStatus("202002"); // 审核状态默认为未提交

        int insertCourseBase = courseBaseMapper.insert(courseBase);

        // 此id为courseBase插入后生成的主键
        Long id = courseBase.getId();

        // 再将数据插入course_market表中
        CourseMarket courseMarket = new CourseMarket();
        BeanUtils.copyProperties(addCourseDto, courseMarket);
        courseMarket.setId(id);
        int insertCourseMarket = courseMarketMapper.insert(courseMarket);

        if (insertCourseMarket <= 0 || insertCourseBase <= 0) {
            throw new RuntimeException("添加课程失败");
        }

        String mt = addCourseDto.getMt();
        String st = addCourseDto.getSt();
        CourseCategory mtCategory = courseCategoryMapper.selectById(mt);
        CourseCategory stCategory = courseCategoryMapper.selectById(st);
        String mtName = null;
        String stName = null;
        if (!Objects.isNull(mtCategory)) {
            mtName = mtCategory.getName();

        }
        if (!Objects.isNull(stCategory)) {
            stName = stCategory.getName();
        }


        // 封装返回信息
        CourseBaseInfoDto courseBaseInfoDto = new CourseBaseInfoDto();
        BeanUtils.copyProperties(courseBase, courseBaseInfoDto);
        BeanUtils.copyProperties(courseMarket, courseBaseInfoDto);
        courseBaseInfoDto.setMtName(mtName);
        courseBaseInfoDto.setStName(stName);
        return courseBaseInfoDto;
    }
}
