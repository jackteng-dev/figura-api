package dev.jackteng.figura.utils;

import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
public class ListPageUtil<T> {
    private List<T> data;
    private int pageNum;
    private int pageSize;
    private int totalCount;
    private int totalPage;
    private int lastPage;
    private int nextPage;

    public ListPageUtil(List<T> data,int pageNum,int pageSize) {
        this.data = data;
        this.pageSize = pageSize;
        this.pageNum = pageNum;
        this.totalCount = data.size();
        this.totalPage = (totalCount + pageSize -1)/pageSize;
        this.lastPage = pageNum-1>1?pageNum-1:1;
        this.nextPage = pageNum>=totalPage?totalPage:pageNum+1;
    }
    public List<T> getPageList(){
        int fromIndex = (pageNum -1) * pageSize;
        if(fromIndex >=data.size()){
            return Collections.emptyList();
        }
        if(fromIndex<0){
            return Collections.emptyList();
        }
        int toIndex = pageNum*pageSize;
        if(toIndex>=data.size()){
            toIndex = data.size();
        }
        return data.subList(fromIndex,toIndex);
    }

}
