package com.github.unknownnpc.scalaxcom.logging

import org.slf4j.Logger
import org.slf4j.LoggerFactory

private[scalaxcom] trait Logging:
  protected val logger: Logger = LoggerFactory.getLogger(getClass)
