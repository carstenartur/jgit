/*
 * Copyright (C) 2026, Carsten Hammer and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.storage.hibernate.search;

/**
 * Data transfer object that holds searchable metadata extracted from a file.
 * <p>
 * Each {@link FileTypeStrategy} populates the fields it can extract. Fields
 * that are not applicable to a particular file type remain {@code null}.
 * </p>
 */
public class BlobIndexData {

	private String fileType;

	private String packageOrNamespace;

	private String declaredTypes;

	private String fullyQualifiedNames;

	private String declaredMethods;

	private String declaredFields;

	private String extendsTypes;

	private String implementsTypes;

	private String importStatements;

	private String sourceSnippet;

	private String projectName;

	private String simpleClassName;

	private String typeKind;

	private String visibility;

	private String annotations;

	private int lineCount;

	/**
	 * Get the file type identifier.
	 *
	 * @return the file type
	 */
	public String getFileType() {
		return fileType;
	}

	/**
	 * Set the file type identifier.
	 *
	 * @param fileType
	 *            the file type
	 */
	public void setFileType(String fileType) {
		this.fileType = fileType;
	}

	/**
	 * Get the package or namespace.
	 *
	 * @return the package or namespace
	 */
	public String getPackageOrNamespace() {
		return packageOrNamespace;
	}

	/**
	 * Set the package or namespace.
	 *
	 * @param packageOrNamespace
	 *            the package or namespace
	 */
	public void setPackageOrNamespace(String packageOrNamespace) {
		this.packageOrNamespace = packageOrNamespace;
	}

	/**
	 * Get the declared types.
	 *
	 * @return the declared types
	 */
	public String getDeclaredTypes() {
		return declaredTypes;
	}

	/**
	 * Set the declared types.
	 *
	 * @param declaredTypes
	 *            the declared types
	 */
	public void setDeclaredTypes(String declaredTypes) {
		this.declaredTypes = declaredTypes;
	}

	/**
	 * Get the fully qualified names.
	 *
	 * @return the fully qualified names
	 */
	public String getFullyQualifiedNames() {
		return fullyQualifiedNames;
	}

	/**
	 * Set the fully qualified names.
	 *
	 * @param fullyQualifiedNames
	 *            the fully qualified names
	 */
	public void setFullyQualifiedNames(String fullyQualifiedNames) {
		this.fullyQualifiedNames = fullyQualifiedNames;
	}

	/**
	 * Get the declared methods.
	 *
	 * @return the declared methods
	 */
	public String getDeclaredMethods() {
		return declaredMethods;
	}

	/**
	 * Set the declared methods.
	 *
	 * @param declaredMethods
	 *            the declared methods
	 */
	public void setDeclaredMethods(String declaredMethods) {
		this.declaredMethods = declaredMethods;
	}

	/**
	 * Get the declared fields.
	 *
	 * @return the declared fields
	 */
	public String getDeclaredFields() {
		return declaredFields;
	}

	/**
	 * Set the declared fields.
	 *
	 * @param declaredFields
	 *            the declared fields
	 */
	public void setDeclaredFields(String declaredFields) {
		this.declaredFields = declaredFields;
	}

	/**
	 * Get the extends types.
	 *
	 * @return the extends types
	 */
	public String getExtendsTypes() {
		return extendsTypes;
	}

	/**
	 * Set the extends types.
	 *
	 * @param extendsTypes
	 *            the extends types
	 */
	public void setExtendsTypes(String extendsTypes) {
		this.extendsTypes = extendsTypes;
	}

	/**
	 * Get the implements types.
	 *
	 * @return the implements types
	 */
	public String getImplementsTypes() {
		return implementsTypes;
	}

	/**
	 * Set the implements types.
	 *
	 * @param implementsTypes
	 *            the implements types
	 */
	public void setImplementsTypes(String implementsTypes) {
		this.implementsTypes = implementsTypes;
	}

	/**
	 * Get the import statements.
	 *
	 * @return the import statements
	 */
	public String getImportStatements() {
		return importStatements;
	}

	/**
	 * Set the import statements.
	 *
	 * @param importStatements
	 *            the import statements
	 */
	public void setImportStatements(String importStatements) {
		this.importStatements = importStatements;
	}

	/**
	 * Get the source snippet.
	 *
	 * @return the source snippet
	 */
	public String getSourceSnippet() {
		return sourceSnippet;
	}

	/**
	 * Set the source snippet.
	 *
	 * @param sourceSnippet
	 *            the source snippet
	 */
	public void setSourceSnippet(String sourceSnippet) {
		this.sourceSnippet = sourceSnippet;
	}

	/**
	 * Get the project name.
	 *
	 * @return the project name
	 */
	public String getProjectName() {
		return projectName;
	}

	/**
	 * Set the project name.
	 *
	 * @param projectName
	 *            the project name
	 */
	public void setProjectName(String projectName) {
		this.projectName = projectName;
	}

	/**
	 * Get the simple class name.
	 *
	 * @return the simple class name
	 */
	public String getSimpleClassName() {
		return simpleClassName;
	}

	/**
	 * Set the simple class name.
	 *
	 * @param simpleClassName
	 *            the simple class name
	 */
	public void setSimpleClassName(String simpleClassName) {
		this.simpleClassName = simpleClassName;
	}

	/**
	 * Get the type kind (class, interface, enum, record, annotation).
	 *
	 * @return the type kind
	 */
	public String getTypeKind() {
		return typeKind;
	}

	/**
	 * Set the type kind.
	 *
	 * @param typeKind
	 *            the type kind
	 */
	public void setTypeKind(String typeKind) {
		this.typeKind = typeKind;
	}

	/**
	 * Get the visibility (public, package, abstract, final etc.).
	 *
	 * @return the visibility
	 */
	public String getVisibility() {
		return visibility;
	}

	/**
	 * Set the visibility.
	 *
	 * @param visibility
	 *            the visibility
	 */
	public void setVisibility(String visibility) {
		this.visibility = visibility;
	}

	/**
	 * Get the newline-separated annotation names.
	 *
	 * @return the annotations
	 */
	public String getAnnotations() {
		return annotations;
	}

	/**
	 * Set the annotations.
	 *
	 * @param annotations
	 *            the newline-separated annotation names
	 */
	public void setAnnotations(String annotations) {
		this.annotations = annotations;
	}

	/**
	 * Get the line count.
	 *
	 * @return the line count
	 */
	public int getLineCount() {
		return lineCount;
	}

	/**
	 * Set the line count.
	 *
	 * @param lineCount
	 *            the line count
	 */
	public void setLineCount(int lineCount) {
		this.lineCount = lineCount;
	}
}
