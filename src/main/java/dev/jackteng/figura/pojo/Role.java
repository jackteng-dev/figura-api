package dev.jackteng.figura.pojo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;


@Data
public class Role {
    private Integer id;
    private String name;
    private String description;
    private List<Permission> permission = new ArrayList<>();
}
