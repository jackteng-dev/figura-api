package com.shop.elpsy.anno;

import com.shop.elpsy.validation.StateValidation;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented//元註解
@Target({ FIELD })//元註解，可以放到哪些類型上
@Retention(RUNTIME)//元註解，哪個階段能被保留
@Constraint(validatedBy = {StateValidation.class})//指定提供校驗規則的類
public @interface State {
    //提供較驗失敗後的提示訊息
    String message() default "state參數的值只能是'已發布'或'草稿'";
    //指定分組
    Class<?>[] groups() default { };
    //負載 獲取State註解的附加訊息
    Class<? extends Payload>[] payload() default { };
}
