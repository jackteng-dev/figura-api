package dev.jackteng.figura.service;
import dev.jackteng.figura.dto.InventoryPayloads.*;
import dev.jackteng.figura.dto.CatalogPayloads.Page;
import dev.jackteng.figura.mapper.CatalogMapper;
import dev.jackteng.figura.mapper.InventoryMapper;
import dev.jackteng.figura.mapper.FulfillmentMapper;
import dev.jackteng.figura.pojo.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InventoryService {
    private final CatalogMapper catalog;
    private final InventoryMapper inventory;
    private final FulfillmentMapper staff;
    public InventoryService(CatalogMapper catalog,InventoryMapper inventory,FulfillmentMapper staff){this.catalog=catalog;this.inventory=inventory;this.staff=staff;}
    private ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private InventoryBalance require(InventoryBalance balance){if(balance==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到商品款式庫存");return balance;}
    @Transactional(readOnly=true) public Balance balance(String productId,String skuId){var b=require(inventory.find(productId,skuId));return new Balance(b.getSkuId(),b.getQuantity(),b.getVersion(),b.getReserved(),b.getQuantity()-b.getReserved());}
    @Transactional(readOnly=true) public Page<Adjustment> history(String productId,String skuId,int page,int pageSize){
        require(inventory.find(productId,skuId));if(page<1||page>100000||pageSize<1||pageSize>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分頁範圍不正確");
        return new Page<>(inventory.history(skuId,pageSize,(page-1)*pageSize).stream().map(Adjustment::from).toList(),page,pageSize,inventory.count(skuId));
    }
    @Transactional public Applied adjust(String productId,String skuId,Adjust input,CustomUserDetails actor){
        // Same actor-first order as refunds; the audit FK also locks this user.
        String username=staff.lockActor(actor.getId());if(username==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"員工登入狀態無效");
        if(staff.lockPermission(actor.getId(),"inventory.adjust").isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"無庫存調整權限");
        // Then product -> SKU/inventory -> ledger, shared by catalog/stock writers.
        var product=catalog.lock(productId);if(product==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"找不到商品款式庫存");
        var b=require(inventory.lock(productId,skuId));var old=inventory.adjustment(input.requestId());
        if(old!=null){
            if(old.getActorId()!=actor.getId()||!old.getSkuId().equals(skuId)||old.getDelta()!=input.delta()||old.getBeforeVersion()!=input.expectedVersion()||!old.getReason().equals(input.reason())||!old.getNote().equals(input.note().trim()))throw conflict("操作編號已被使用，請重新核對");
            return new Applied(Adjustment.from(old),true);
        }
        if("ARCHIVED".equals(product.getStatus()))throw conflict("封存商品不可調整庫存");
        if(b.getVersion()!=input.expectedVersion())throw conflict("庫存已被修改，請重新載入後核對");
        long after=(long)b.getQuantity()+input.delta();if(after<0||after>1000000)throw conflict("調整後庫存需介於 0 與 1,000,000");if(after<b.getReserved())throw conflict("實體庫存不可低於訂單保留量");
        var entry=new InventoryAdjustment();entry.setRequestId(input.requestId());entry.setSkuId(skuId);entry.setActorId(actor.getId());entry.setActorUsername(username);entry.setDelta(input.delta());entry.setReason(input.reason());entry.setNote(input.note().trim());entry.setBeforeQuantity(b.getQuantity());entry.setAfterQuantity((int)after);entry.setBeforeVersion(b.getVersion());entry.setAfterVersion(b.getVersion()+1);
        b.setQuantity((int)after);if(inventory.update(b)!=1)throw conflict("庫存已被修改，請重新載入後核對");
        try{inventory.append(entry);}catch(DuplicateKeyException e){throw conflict("操作編號已被使用，請重新核對");}
        return new Applied(Adjustment.from(inventory.adjustment(input.requestId())),false);
    }
}
