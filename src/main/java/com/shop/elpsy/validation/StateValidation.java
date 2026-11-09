package com.shop.elpsy.validation;

import com.shop.elpsy.anno.State;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class StateValidation implements ConstraintValidator<State,String> {
    /**
     *
     * @param value 將來要校驗的數據
     * @param context context in which the constraint is evaluated
     *
     * @return 如果返還false，代表不通過 ; 返回true，代表通過
     */

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        //提供校驗規則
        if(value == null){
            return false;
        }
        if(value.equals("已發布") || value.equals("草稿")){
            return  true;
        }
        return false;
    }
}
