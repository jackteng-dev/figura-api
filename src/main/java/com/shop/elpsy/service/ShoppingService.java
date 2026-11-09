package com.shop.elpsy.service;

import com.shop.elpsy.dto.ShoppingPayloads.*;
import com.shop.elpsy.mapper.CatalogMapper;
import com.shop.elpsy.mapper.ShoppingMapper;
import com.shop.elpsy.pojo.CustomerAddress;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class ShoppingService {
    private final ShoppingMapper mapper;
    private final CatalogMapper catalog;
    public ShoppingService(ShoppingMapper mapper,CatalogMapper catalog){this.mapper=mapper;this.catalog=catalog;}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private void id(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw error(HttpStatus.BAD_REQUEST,"ID 格式不正確");}}
    private void lock(String customer){if(mapper.lockCustomer(customer)==null)throw error(HttpStatus.UNAUTHORIZED,"顧客登入狀態無效");}
    private void cartVersion(String customer,int expected){if(expected<0)throw error(HttpStatus.BAD_REQUEST,"版本不正確");var current=mapper.cartVersion(customer);if((current==null?0:current)!=expected)throw error(HttpStatus.CONFLICT,"購物車已變更，請重新核對");}
    @Transactional(readOnly=true)
    public CartView cart(String customer){
        var version=mapper.cartVersion(customer);long subtotal=0;var lines=new ArrayList<CartLine>();
        for(var item:mapper.items(customer)){
            boolean published="PUBLISHED".equals(item.getStatus())&&item.isEnabled();
            String issue=!published?"UNAVAILABLE":item.getStock()==0?"SOLD_OUT":item.getQuantity()>item.getStock()?"INSUFFICIENT_STOCK":null;
            Long total=published?(long)item.getPriceTwd()*item.getQuantity():null;
            if(issue==null)subtotal+=total;
            lines.add(new CartLine(item.getSkuId(),item.getProductId(),published?item.getTitle():item.getTitleSnapshot(),published?item.getLabel():item.getLabelSnapshot(),item.getQuantity(),published?item.getPriceTwd():null,total,issue));
        }
        return new CartView(version==null?0:version,lines,subtotal,!lines.isEmpty()&&lines.stream().allMatch(l->l.issue()==null));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public CartView setItem(String customer,String sku,CartWrite input){
        id(sku);lock(customer);cartVersion(customer,input.expectedVersion());
        String productId=mapper.productForSku(sku);if(productId==null)throw error(HttpStatus.NOT_FOUND,"找不到款式");
        // Same product-first order as catalog/inventory; the cart never reserves or changes stock.
        var product=catalog.lock(productId);
        var option=catalog.lockSkus(productId).stream().filter(s->s.getId().equals(sku)).findFirst().orElseThrow();
        if(!"PUBLISHED".equals(product.getStatus())||!option.isEnabled())throw error(HttpStatus.CONFLICT,"商品或款式已下架");
        if(input.quantity()>option.getQuantity())throw error(HttpStatus.CONFLICT,"目前庫存不足，請重新核對");
        if(mapper.contains(customer,sku)==0&&mapper.cartCount(customer)>=50)throw error(HttpStatus.CONFLICT,"購物車最多 50 種款式");
        mapper.ensureCart(customer);mapper.setItem(customer,sku,input.quantity(),product.getTitle(),option.getLabel());mapper.bumpCart(customer);return cart(customer);
    }
    @Transactional
    public CartView removeItem(String customer,String sku,int expected){
        id(sku);lock(customer);cartVersion(customer,expected);
        if(mapper.removeItem(customer,sku)!=1)throw error(HttpStatus.NOT_FOUND,"購物車沒有此款式");
        mapper.bumpCart(customer);return cart(customer);
    }
    private AddressView view(CustomerAddress a){return new AddressView(a.getId(),a.getVersion(),a.getRecipientName(),a.getPhone(),a.getPostalCode(),a.getCity(),a.getDistrict(),a.getStreet());}
    @Transactional(readOnly=true) public List<AddressView> addresses(String customer){return mapper.addresses(customer).stream().map(this::view).toList();}
    @Transactional
    public AddressView saveAddress(String customer,String addressId,AddressWrite input){
        id(addressId);lock(customer);var address=mapper.address(addressId);
        if(address!=null&&!address.getCustomerId().equals(customer))throw error(HttpStatus.NOT_FOUND,"找不到地址");
        if(address!=null&&address.isDeleted())throw error(HttpStatus.CONFLICT,"地址已刪除，請使用新的地址編號");
        if(address==null){
            if(input.expectedVersion()!=0)throw error(HttpStatus.NOT_FOUND,"找不到地址");
            if(mapper.addressCount(customer)>=10)throw error(HttpStatus.CONFLICT,"最多保存 10 個地址");
            address=new CustomerAddress();address.setId(addressId);address.setCustomerId(customer);
        }else if(address.getVersion()!=input.expectedVersion())throw error(HttpStatus.CONFLICT,"地址已變更，請重新核對");
        address.setRecipientName(input.recipientName().strip());address.setPhone(input.phone());address.setPostalCode(input.postalCode());address.setCity(input.city().strip());address.setDistrict(input.district().strip());address.setStreet(input.street().strip());
        if(input.expectedVersion()==0){try{mapper.insertAddress(address);}catch(DuplicateKeyException e){throw error(HttpStatus.CONFLICT,"地址編號已使用，請重新核對");}}
        else if(mapper.updateAddress(address)!=1)throw error(HttpStatus.CONFLICT,"地址已變更，請重新核對");
        return view(mapper.address(addressId));
    }
    @Transactional public void deleteAddress(String customer,String addressId,int expected){
        id(addressId);if(expected<1)throw error(HttpStatus.BAD_REQUEST,"版本不正確");lock(customer);
        var address=mapper.address(addressId);if(address==null||!address.getCustomerId().equals(customer)||address.isDeleted())throw error(HttpStatus.NOT_FOUND,"找不到地址");
        if(mapper.deleteAddress(customer,addressId,expected)!=1)throw error(HttpStatus.CONFLICT,"地址已變更，請重新核對");
    }
}
