package com.shop.elpsy.controller;

import com.shop.elpsy.pojo.Article;
import com.shop.elpsy.pojo.PageBean;
import com.shop.elpsy.pojo.Result;
import com.shop.elpsy.service.ArticleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/article")
public class ArticleController {
    @Autowired
    private ArticleService articleService;

    @PostMapping
    public Result add(@RequestBody @Validated(Article.Add.class) Article article){
        articleService.add(article);
        return  Result.success();
    }
    @GetMapping
    public Result<PageBean<Article>> list(
            Integer pageNum,
            Integer pageSize,
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false)String state
    ){
        PageBean<Article> pb = articleService.list(pageNum,pageSize,categoryId,state);
        return Result.success(pb);
    }
    @PutMapping
    public Result update(@RequestBody @Validated(Article.Update.class) Article article){
        articleService.update(article);
        return Result.success();
    }
    @DeleteMapping
    public Result delete(Integer id){
        if(articleService.findById(id) == null){
            return Result.error("沒有這筆資料");
        };
        articleService.delete(id);
        return Result.success();
    }
}
