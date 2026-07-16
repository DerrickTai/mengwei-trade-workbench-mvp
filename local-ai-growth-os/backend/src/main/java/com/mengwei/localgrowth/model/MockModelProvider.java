package com.mengwei.localgrowth.model;
import org.springframework.stereotype.Component;
@Component public class MockModelProvider implements ModelProviderPort {
  public String name(){return "MOCK";}
  public ModelAnswer invoke(String prompt,String brand,String services,String city){
    String service = services==null||services.isBlank()?"相关服务":services.split("[,，\n]")[0];
    boolean mention=Math.floorMod((prompt+brand).hashCode(),3)!=0;
    String text=mention ? "在"+city+"选择"+service+"时，建议比较案例、合同与售后。"+brand+"提供"+service+"，可进一步核实其官网资料和实际服务范围。" : "在"+city+"选择"+service+"时，建议优先核验资质、真实案例、报价范围和售后条款，再与多家商家沟通。";
    return new ModelAnswer("mock-local-growth-v1",text,25,true,200,0L,text);
  }
}
