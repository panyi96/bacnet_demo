package com.example.demo.entity;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * @author PanYi
 */
@Data
public class ExcelData {
    @ExcelProperty(index = 4)
    private String code;
    @ExcelProperty(index = 6)
    private String name;
    @ExcelProperty(index = 12)
    private String key;

}
