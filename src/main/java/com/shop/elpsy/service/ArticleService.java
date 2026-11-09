package com.shop.elpsy.service;

import com.shop.elpsy.pojo.Article;
import com.shop.elpsy.pojo.PageBean;

public interface ArticleService {
    //新增文章
    void add(Article article);
    //條件分頁列表查詢
    PageBean<Article> list(Integer pageNum, Integer pageSize, Integer categoryId, String state);
    //編輯文章
    void update(Article article);
    Article findById(Integer id);
    //刪除文章
    void delete(Integer id);
}
