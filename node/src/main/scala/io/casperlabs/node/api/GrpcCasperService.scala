package io.casperlabs.node.api

import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.{state, Block}
import io.casperlabs.casper.consensus.info._
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.validation.Validation
import io.casperlabs.casper.MultiParentCasperRef
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.comm.ServiceError.{InvalidArgument, Unavailable}
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.node.api.Utils.{
  validateAccountPublicKey,
  validateBlockHashPrefix,
  validateDeployHash
}
import io.casperlabs.node.api.casper._
import io.casperlabs.node.api.graphql.FinalizedBlocksStream
import fs2.interop.reactivestreams._
import io.casperlabs.node.{Fs2StreamOps}
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block._
import io.casperlabs.storage.deploy.DeployStorage
import monix.eval.{Task, TaskLike}
import monix.reactive.Observable

object GrpcCasperService {

  def apply[F[_]: Concurrent: TaskLike: Log: Metrics: MultiParentCasperRef: BlockStorage: ExecutionEngineService: DeployStorage: Validation: Fs2Compiler: EventsStream: ConcurrentEffect]()
      : F[CasperGrpcMonix.CasperService] =
    BlockAPI.establishMetrics[F] *> Sync[F].delay {
      val adaptToInvalidArgument: PartialFunction[Throwable, Throwable] = {
        case e => InvalidArgument(e.getMessage)
      }

      new CasperGrpcMonix.CasperService {
        override def deploy(request: DeployRequest): Task[Empty] =
          TaskLike[F].apply {
            BlockAPI.deploy[F](request.getDeploy).map(_ => Empty())
          }

        override def getBlockInfo(request: GetBlockInfoRequest): Task[BlockInfo] =
          TaskLike[F].apply {
            validateBlockHashPrefix[F](request.blockHashBase16, adaptToInvalidArgument) >>= {
              blockHashPrefix =>
                BlockAPI
                  .getBlockInfo[F](
                    blockHashPrefix
                  )
            }
          }

        override def streamBlockInfos(request: StreamBlockInfosRequest): Observable[BlockInfo] = {
          val infos = TaskLike[F].apply {
            BlockAPI.getBlockInfos[F](
              depth = request.depth,
              maxRank = request.maxRank
            )
          }
          Observable.fromTask(infos).flatMap(Observable.fromIterable)
        }

        override def getDeployInfo(request: GetDeployInfoRequest): Task[DeployInfo] =
          TaskLike[F].apply {
            validateDeployHash[F](request.deployHashBase16, adaptToInvalidArgument) >>= {
              deployHash =>
                BlockAPI
                  .getDeployInfo[F](deployHash, request.view)
            }
          }

        override def streamBlockDeploys(
            request: StreamBlockDeploysRequest
        ): Observable[Block.ProcessedDeploy] = {
          val deploys = TaskLike[F].apply {
            validateBlockHashPrefix[F](request.blockHashBase16, adaptToInvalidArgument) >>= {
              blockHashPrefix =>
                BlockAPI.getBlockDeploys[F](blockHashPrefix, request.view)
            }
          }
          Observable.fromTask(deploys).flatMap(Observable.fromIterable)
        }

        override def getBlockState(request: GetBlockStateRequest): Task[state.Value] =
          batchGetBlockState(
            BatchGetBlockStateRequest(request.blockHashBase16, List(request.getQuery))
          ) map {
            _.values.head
          }

        override def batchGetBlockState(
            request: BatchGetBlockStateRequest
        ): Task[BatchGetBlockStateResponse] = TaskLike[F].apply {
          for {
            blockHashPrefix <- validateBlockHashPrefix[F](
                                request.blockHashBase16,
                                adaptToInvalidArgument
                              )
            info            <- BlockAPI.getBlockInfo[F](blockHashPrefix)
            stateHash       = info.getSummary.state.postStateHash
            protocolVersion = info.getSummary.getHeader.getProtocolVersion
            values          <- request.queries.toList.traverse(getState(stateHash, _, protocolVersion))
          } yield BatchGetBlockStateResponse(values)
        }

        private def getState(
            stateHash: ByteString,
            query: StateQuery,
            protocolVersion: ProtocolVersion
        ): F[state.Value] =
          for {
            key <- toKey[F](query.keyVariant, query.keyBase16)
            possibleResponse <- ExecutionEngineService[F].query(
                                 stateHash,
                                 key,
                                 query.pathSegments,
                                 protocolVersion
                               )
            value <- Concurrent[F].fromEither(possibleResponse).handleErrorWith {
                      case SmartContractEngineError(msg) =>
                        MonadThrowable[F].raiseError(InvalidArgument(msg))
                    }
          } yield value

        override def listDeployInfos(
            request: ListDeployInfosRequest
        ): Task[ListDeployInfosResponse] =
          TaskLike[F].apply {
            for {
              accountPublicKeyBase16 <- validateAccountPublicKey[F](
                                         request.accountPublicKeyBase16,
                                         adaptToInvalidArgument
                                       )
              (pageSize, pageTokenParams) <- MonadThrowable[F].fromTry(
                                              DeployInfoPagination
                                                .parsePageToken(
                                                  request
                                                )
                                            )
              accountPublicKeyBs = PublicKey(
                ByteString.copyFrom(
                  Base16.decode(accountPublicKeyBase16)
                )
              )
              deploys <- DeployStorage[F]
                          .reader(request.view)
                          .getDeploysByAccount(
                            accountPublicKeyBs,
                            pageSize,
                            pageTokenParams.lastTimeStamp,
                            pageTokenParams.lastDeployHash,
                            pageTokenParams.isNext
                          )
              deployInfos <- DeployStorage[F]
                              .reader(request.view)
                              .getDeployInfos(deploys)
              (nextPageToken, prevPageToken) = DeployInfoPagination.createNextAndPrePageToken(
                deploys,
                pageTokenParams
              )
              result = ListDeployInfosResponse()
                .withDeployInfos(deployInfos)
                .withNextPageToken(nextPageToken)
                .withPrevPageToken(prevPageToken)
            } yield result
          }

        override def getLastFinalizedBlockInfo(
            request: GetLastFinalizedBlockInfoRequest
        ): Task[BlockInfo] =
          TaskLike[F].apply {
            MultiParentCasperRef
              .withCasper[F, BlockInfo](
                _.lastFinalizedBlock.map(_.getBlockInfo),
                "Could not get last finalized block.",
                MonadThrowable[F].raiseError(Unavailable("Casper instance not available yet."))
              )
          }

        override def streamEvents(request: StreamEventsRequest): Observable[Event] =
          EventsStream[F].subscribe.toMonixObservable
      }
    }

  def toKey[F[_]: MonadThrowable](
      keyType: StateQuery.KeyVariant,
      keyValue: String
  ): F[state.Key] =
    Utils.toKey[F](keyType.name, keyValue).handleErrorWith {
      case ex: java.lang.IllegalArgumentException =>
        MonadThrowable[F].raiseError(InvalidArgument(ex.getMessage))
    }
}
