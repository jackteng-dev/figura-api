package com.shop.elpsy.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.shop.elpsy.mapper.ArticleMapper;
import com.shop.elpsy.pojo.Article;
import com.shop.elpsy.pojo.PageBean;
import com.shop.elpsy.service.ArticleService;
import com.shop.elpsy.utils.ThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class ArticleServiceImpl implements ArticleService {
    @Autowired
    private ArticleMapper articleMapper;

    @Override
    public void add(Article article) {
        article.setCreateTime(LocalDateTime.now());
        article.setUpdateTime(LocalDateTime.now());
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer userId = (Integer)map.get("id");
        article.setCreateUser(userId);
        articleMapper.add(article);
    }

    @Override
    public PageBean<Article> list(Integer pageNum, Integer pageSize, Integer categoryId, String state) {
        //創建PageBean對象
        PageBean<Article> pb = new PageBean<>();

        //開啟分頁查詢 PageHelper
        PageHelper.startPage(pageNum,pageSize);

        //調用Mapper
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer userId = (Integer)map.get("id");
        List<Article> as = articleMapper.list(userId,categoryId,state);
        //Page中提供了方法，可以得到PageHelper分頁查詢後得到的總紀錄條數和當前頁面數據
        Page<Article> p = (Page<Article>) as;

        //把數據填充到PageBean
        pb.setTotal(p.getTotal());
        pb.setItems(p.getResult());
        return pb;
    }

    @Override
    public void update(Article article) {
        article.setUpdateTime(LocalDateTime.now());
        articleMapper.update(article);
    }

    @Override
    public Article findById(Integer id) {
        return articleMapper.findById(id);
    }

    @Override
    public void delete(Integer id) {
        articleMapper.delete(id);
    }
}
