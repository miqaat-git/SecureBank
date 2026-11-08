<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title><c:out value="${pageTitle}"/></title>
</head>
<body>
<main>
    <h1><c:out value="${pageTitle}"/></h1>
    <p id="el-check">EL arithmetic: <c:out value="${1 + 2}"/></p>

    <h2>Items rendered with c:forEach</h2>
    <ul id="items">
        <c:forEach var="item" items="${items}">
            <li><c:out value="${item}"/></li>
        </c:forEach>
    </ul>

    <h2>Runtime versions</h2>
    <ul id="versions">
        <c:forEach var="entry" items="${versions}">
            <li><c:out value="${entry.key}"/>: <c:out value="${entry.value}"/></li>
        </c:forEach>
    </ul>
</main>
<footer>
    <p>Educational banking simulator. Fictional customers and simulated funds. Not a real bank.</p>
</footer>
</body>
</html>
