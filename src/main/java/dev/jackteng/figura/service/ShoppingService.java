package dev.jackteng.figura.service;

import dev.jackteng.figura.dto.ShoppingPayloads.*;
import dev.jackteng.figura.mapper.CatalogMapper;
import dev.jackteng.figura.mapper.ShoppingMapper;
import dev.jackteng.figura.pojo.CustomerAddress;
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
    private final org.springframework.jdbc.core.JdbcTemplate db;
    public ShoppingService(ShoppingMapper mapper,CatalogMapper catalog,org.springframework.jdbc.core.JdbcTemplate db){this.mapper=mapper;this.catalog=catalog;this.db=db;}
    private static final java.time.ZoneOffset TAIPEI=java.time.ZoneOffset.ofHours(8);
    /** 購物車品項上限（現貨款式＋預購批次合計）。 */
    static final int MAX_LINES=50;
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
        var preorders=preorderLines(customer);long preorderSubtotal=preorders.stream().filter(p->p.issue()==null).mapToLong(PreorderLine::lineTotalTwd).sum();
        boolean stockReady=!lines.isEmpty()&&lines.stream().allMatch(l->l.issue()==null);
        boolean checkoutReady=(!lines.isEmpty()||!preorders.isEmpty())&&lines.stream().allMatch(l->l.issue()==null)&&preorders.stream().allMatch(p->p.issue()==null);
        return new CartView(version==null?0:version,lines,subtotal,stockReady,preorders,preorderSubtotal,subtotal+preorderSubtotal,checkoutReady);
    }
    /** 預購項目：價格、名稱、到貨時間、剩餘配額都即時取自批次；購物車不保留配額。 */
    private List<PreorderLine> preorderLines(String customer){
        return db.query("""
            SELECT c.batch_id, c.quantity, b.product_id, b.sku_id, b.name, b.product_title, b.sku_label, b.price_twd, b.deadline_at, b.arrival_from, b.arrival_to, b.payment_mode, b.deposit_twd,
                   b.status, b.capacity - b.held_quantity - b.paid_quantity available, b.deadline_at <= CURRENT_TIMESTAMP(6) expired,
                   (p.status='PUBLISHED' AND s.enabled) sellable
              FROM customer_cart_preorder c JOIN preorder_batch b ON b.id=c.batch_id
              LEFT JOIN catalog_product p ON p.id=b.product_id LEFT JOIN catalog_sku s ON s.id=b.sku_id
             WHERE c.customer_id=? ORDER BY b.arrival_from, b.arrival_to, c.added_at, c.batch_id""",(r,n)->{
            int qty=r.getInt("quantity"),price=r.getInt("price_twd"),available=Math.max(0,r.getInt("available"));
            String issue=!r.getBoolean("sellable")?"UNAVAILABLE":!"OPEN".equals(r.getString("status"))?"CLOSED":r.getBoolean("expired")?"EXPIRED":qty>available?"INSUFFICIENT_QUOTA":null;
            var deadline=r.getObject("deadline_at",java.time.LocalDateTime.class);
            return new PreorderLine(r.getString("batch_id"),r.getString("product_id"),r.getString("sku_id"),r.getString("product_title"),r.getString("name"),r.getString("sku_label"),
                    qty,price,(long)price*qty,deadline==null?null:deadline.atOffset(TAIPEI),r.getObject("arrival_from",java.time.LocalDate.class),r.getObject("arrival_to",java.time.LocalDate.class),available,issue,
                    r.getString("payment_mode"),(Integer)r.getObject("deposit_twd"));
        },customer);
    }
    private int lineCount(String customer){
        return mapper.cartCount(customer)+db.queryForObject("SELECT COUNT(*) FROM customer_cart_preorder WHERE customer_id=?",Integer.class,customer);
    }
    /** 預購加入購物車／改數量：只檢查目前可預購與剩餘配額，不保留配額（下單時才保留）。 */
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public CartView setPreorder(String customer,String batchId,PreorderCartWrite input){
        id(batchId);lock(customer);cartVersion(customer,input.expectedVersion());
        var rows=db.queryForList("""
            SELECT b.status, b.deadline_at > CURRENT_TIMESTAMP(6) open_time, b.capacity - b.held_quantity - b.paid_quantity available,
                   (p.status='PUBLISHED' AND s.enabled) sellable
              FROM preorder_batch b LEFT JOIN catalog_product p ON p.id=b.product_id LEFT JOIN catalog_sku s ON s.id=b.sku_id WHERE b.id=?""",batchId);
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"找不到預購批次");
        var b=rows.get(0);
        if(!truthy(b.get("sellable")))throw error(HttpStatus.CONFLICT,"預購商品或款式已下架");
        if(!"OPEN".equals(b.get("status"))||!truthy(b.get("open_time")))throw error(HttpStatus.CONFLICT,"批次尚未開放或已截單");
        if(input.quantity()>((Number)b.get("available")).intValue())throw error(HttpStatus.CONFLICT,"批次配額不足，請重新核對");
        boolean exists=db.queryForObject("SELECT COUNT(*) FROM customer_cart_preorder WHERE customer_id=? AND batch_id=?",Integer.class,customer,batchId)>0;
        if(!exists&&lineCount(customer)>=MAX_LINES)throw error(HttpStatus.CONFLICT,"購物車最多 50 種品項");
        mapper.ensureCart(customer);
        db.update("INSERT INTO customer_cart_preorder(customer_id,batch_id,quantity) VALUES(?,?,?) ON DUPLICATE KEY UPDATE quantity=VALUES(quantity)",customer,batchId,input.quantity());
        mapper.bumpCart(customer);return cart(customer);
    }
    @Transactional
    public CartView removePreorder(String customer,String batchId,int expected){
        id(batchId);lock(customer);cartVersion(customer,expected);
        if(db.update("DELETE FROM customer_cart_preorder WHERE customer_id=? AND batch_id=?",customer,batchId)!=1)throw error(HttpStatus.NOT_FOUND,"購物車沒有此預購");
        mapper.bumpCart(customer);return cart(customer);
    }
    private static boolean truthy(Object v){return v instanceof Boolean x?x:v instanceof Number n&&n.intValue()!=0;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public CartView setItem(String customer,String sku,CartWrite input){
        id(sku);lock(customer);cartVersion(customer,input.expectedVersion());
        String productId=mapper.productForSku(sku);if(productId==null)throw error(HttpStatus.NOT_FOUND,"找不到款式");
        // Same product-first order as catalog/inventory; the cart never reserves or changes stock.
        var product=catalog.lock(productId);
        var option=catalog.lockSkus(productId).stream().filter(s->s.getId().equals(sku)).findFirst().orElseThrow();
        if(!"PUBLISHED".equals(product.getStatus())||!option.isEnabled())throw error(HttpStatus.CONFLICT,"商品或款式已下架");
        if(input.quantity()>option.getQuantity())throw error(HttpStatus.CONFLICT,"目前庫存不足，請重新核對");
        if(mapper.contains(customer,sku)==0&&lineCount(customer)>=MAX_LINES)throw error(HttpStatus.CONFLICT,"購物車最多 50 種品項");
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
