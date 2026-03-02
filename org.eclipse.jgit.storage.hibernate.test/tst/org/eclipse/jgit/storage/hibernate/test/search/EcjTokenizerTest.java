/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.test.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.eclipse.jgit.storage.hibernate.search.EcjTokenFilter;
import org.eclipse.jgit.storage.hibernate.search.EcjTokenizer;
import org.junit.Test;

/**
 * Tests for {@link EcjTokenizer} and {@link EcjTokenFilter}.
 */
public class EcjTokenizerTest {

	@Test
	public void testSimpleClass() throws Exception {
		String input = "public class Foo { int myVar = 42; }";
		List<TokenInfo> tokens = tokenize(input);
		assertFalse("Should produce tokens", tokens.isEmpty());

		// Should contain keywords: public, class, int
		assertTrue("Should contain 'public' keyword",
				containsToken(tokens, "public", EcjTokenizer.TYPE_KEYWORD));
		assertTrue("Should contain 'class' keyword",
				containsToken(tokens, "class", EcjTokenizer.TYPE_KEYWORD));
		assertTrue("Should contain 'int' keyword",
				containsToken(tokens, "int", EcjTokenizer.TYPE_KEYWORD));

		// Should contain identifiers: Foo, myVar
		assertTrue("Should contain 'Foo' identifier",
				containsToken(tokens, "Foo", EcjTokenizer.TYPE_IDENTIFIER));
		assertTrue("Should contain 'myVar' identifier",
				containsToken(tokens, "myVar", EcjTokenizer.TYPE_IDENTIFIER));

		// Should contain number literal: 42
		assertTrue("Should contain '42' number",
				containsToken(tokens, "42", EcjTokenizer.TYPE_NUMBER_LITERAL));
	}

	@Test
	public void testStringLiteral() throws Exception {
		String input = "String s = \"hello world\";";
		List<TokenInfo> tokens = tokenize(input);

		assertTrue("Should contain string literal content",
				containsToken(tokens, "hello world",
						EcjTokenizer.TYPE_STRING_LITERAL));
	}

	@Test
	public void testSyntaxError() throws Exception {
		// Malformed input should degrade gracefully
		String input = "public class { int x = @#$; }";
		List<TokenInfo> tokens = tokenize(input);
		// Should still produce some tokens without throwing
		assertFalse("Should produce some tokens despite errors",
				tokens.isEmpty());
	}

	@Test
	public void testFilterCamelCaseSplitting() throws Exception {
		String input = "int mySpecialVar;";
		List<String> terms = tokenizeWithFilter(input);

		assertTrue("Should contain original 'mySpecialVar'",
				terms.contains("mySpecialVar"));
		assertTrue("Should contain 'my' from CamelCase split",
				terms.contains("my"));
		assertTrue("Should contain 'Special' from CamelCase split",
				terms.contains("Special"));
		assertTrue("Should contain 'Var' from CamelCase split",
				terms.contains("Var"));
	}

	@Test
	public void testFilterSkipsOperators() throws Exception {
		String input = "int x = 1 + 2;";
		List<String> terms = tokenizeWithFilter(input);

		// Operators like =, +, ; should be skipped
		assertFalse("Should not contain '='", terms.contains("="));
		assertFalse("Should not contain '+'", terms.contains("+"));
		assertFalse("Should not contain ';'", terms.contains(";"));
	}

	@Test
	public void testFilterPassesKeywords() throws Exception {
		String input = "synchronized void doWork() {}";
		List<String> terms = tokenizeWithFilter(input);

		assertTrue("Should contain 'synchronized' keyword",
				terms.contains("synchronized"));
		assertTrue("Should contain 'void' keyword",
				terms.contains("void"));
	}

	@Test
	public void testCamelCaseSplit() {
		String[] parts = EcjTokenFilter.splitCamelCase("mySpecialVar");
		assertEquals(3, parts.length);
		assertEquals("my", parts[0]);
		assertEquals("Special", parts[1]);
		assertEquals("Var", parts[2]);
	}

	@Test
	public void testCamelCaseSplitSingleWord() {
		String[] parts = EcjTokenFilter.splitCamelCase("foo");
		assertEquals(1, parts.length);
		assertEquals("foo", parts[0]);
	}

	@Test
	public void testCamelCaseSplitEmpty() {
		String[] parts = EcjTokenFilter.splitCamelCase("");
		assertEquals(0, parts.length);
	}

	private List<TokenInfo> tokenize(String input) throws IOException {
		List<TokenInfo> tokens = new ArrayList<>();
		try (EcjTokenizer tokenizer = new EcjTokenizer()) {
			tokenizer.setReader(new StringReader(input));
			tokenizer.reset();
			CharTermAttribute termAttr = tokenizer
					.addAttribute(CharTermAttribute.class);
			TypeAttribute typeAttr = tokenizer
					.addAttribute(TypeAttribute.class);
			while (tokenizer.incrementToken()) {
				tokens.add(new TokenInfo(termAttr.toString(),
						typeAttr.type()));
			}
			tokenizer.end();
		}
		return tokens;
	}

	private List<String> tokenizeWithFilter(String input) throws IOException {
		List<String> terms = new ArrayList<>();
		try (EcjTokenizer tokenizer = new EcjTokenizer()) {
			tokenizer.setReader(new StringReader(input));
			try (TokenStream filter = new EcjTokenFilter(tokenizer)) {
				filter.reset();
				CharTermAttribute termAttr = filter
						.addAttribute(CharTermAttribute.class);
				while (filter.incrementToken()) {
					terms.add(termAttr.toString());
				}
				filter.end();
			}
		}
		return terms;
	}

	private static boolean containsToken(List<TokenInfo> tokens, String term,
			String type) {
		return tokens.stream()
				.anyMatch(t -> t.term.equals(term) && t.type.equals(type));
	}

	private static class TokenInfo {
		final String term;

		final String type;

		TokenInfo(String term, String type) {
			this.term = term;
			this.type = type;
		}
	}
}
