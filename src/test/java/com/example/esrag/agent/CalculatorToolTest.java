package com.example.esrag.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();

    private String calc(String expr) {
        return tool.execute(Map.of("expression", expr));
    }

    @Test
    void basicArithmetic() {
        assertTrue(calc("(3+5)*2").contains("\"result\": 16"));
    }

    @Test
    void powerIsRightAssociative() {
        assertTrue(calc("2^3^2").contains("\"result\": 512"));
    }

    @Test
    void moduloAndUnaryMinus() {
        assertTrue(calc("10%3").contains("\"result\": 1"));
        assertTrue(calc("-5+3").contains("\"result\": -2"));
    }

    @Test
    void decimalAndWhitespace() {
        assertTrue(calc("  1.5 * 2 + 0.5 ").contains("\"result\": 3.5"));
    }

    @Test
    void divisionByZeroReturnsError() {
        assertTrue(calc("1/0").contains("error"));
    }

    @Test
    void invalidExpressionReturnsError() {
        assertTrue(calc("abc").contains("error"));
        assertTrue(calc("(3+5").contains("error"));
    }

    @Test
    void missingExpressionReturnsError() {
        assertTrue(tool.execute(Map.of()).contains("error"));
    }
}