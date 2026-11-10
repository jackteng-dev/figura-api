package dev.jackteng.figura.pojo;

public record CustomerPrincipal(String id) implements java.security.Principal {
    @Override public String getName() { return id; }
}
